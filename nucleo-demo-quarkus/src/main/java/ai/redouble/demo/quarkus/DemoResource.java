/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.quarkus;

import ai.redouble.demo.*;
import ai.redouble.demo.decide.*;
import ai.redouble.demo.extract.*;
import ai.redouble.demo.pricing.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.quarkus.*;
import ai.redouble.nucleo.tools.benchmark.*;
import ai.redouble.nucleo.tools.builtin.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import io.quarkus.runtime.annotations.*;
import jakarta.inject.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import java.io.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The doors of the demo under Quarkus: the same engine the Spring host serves, behind JAX-RS
 * instead of Spring MVC. The read-side ({@code GET /status}, {@code /skills}, {@code /agent}) is
 * computed by the shared {@link DemoApi}, and the catalog lifecycle the page drives ({@code POST
 * /connect}, {@code /discover}, {@code /catalog/entry}, {@code /catalog/order}, {@code /catalog/pin}) by the shared
 * {@link CatalogAdmin} over this host's {@link SessionCredentials}, so neither can drift from the
 * Spring host or the page; this class maps the routes to them and maps a refusal to its status
 * code, carrying the rule it broke as {@code {"message": ...}}. The write-side that runs a job,
 * where a request builds a Tool or a Doer and submits it to the dispatcher, is the demonstration
 * of using the runtime under Quarkus and lives here: {@code POST /ask} runs the quick-question
 * tool, {@code POST /agent} runs the demo agent, and {@code POST /extract}, {@code /search} and
 * {@code /pricing} are the extraction demo. A refusal comes back with the runtime's own message,
 * which says what to provide.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
@Path("/")
public class DemoResource {
    private final NucleoRuntime runtime;
    private final FileIndex index;
    private final CatalogAdmin admin;
    /** The container's own Jackson mapper, so a streamed run's fields are named as the page reads them (camelCase), as the Spring host's are. */
    private final ObjectMapper json;

    @Inject
    public DemoResource(NucleoRuntime runtime, FileIndex index, CatalogAdmin admin, ObjectMapper json) {
        this.runtime = runtime;
        this.index = index;
        this.admin = admin;
        this.json = json;
    }

    @GET
    @Path("skills")
    @Produces(MediaType.APPLICATION_JSON)
    public List<SkillEntry> skills() {
        return DemoApi.skills();
    }

    @GET
    @Path("agent")
    @Produces(MediaType.APPLICATION_JSON)
    public AgentCapabilities agent() {
        return DemoApi.agentCapabilities("anonymous");
    }

    /**
     * The demo agent run, streamed as it happens: the engine's {@link AgentTrace} writes the lines
     * to a queue, and the response drains the queue until the trace ends it, the same as the Spring
     * host's stream and the decision run below. The grade is the caller's; the agent declares MEDIUM
     * and a request naming a rung runs it there.
     */
    @POST
    @Path("agent")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("application/x-ndjson")
    public Response agent(AgentRequest input) {
        if (input.query() == null || input.query().isBlank()) {
            throw refusal(Response.Status.BAD_REQUEST, "A query to run the agent on");
        }
        Identifiable workflow = Job.workflow("anonymous", "agent");
        DemoAgent agent = new DemoAgent(workflow);
        DemoQuery query = new DemoQuery();
        query.setQuery(input.query());
        agent.setInput(query);
        if (input.grade() != null) {
            agent.setGrade(input.grade());
        }
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        AgentTrace trace = new AgentTrace(this::toJson, workflow.getWorkflowId(), agent.getId(), lines::add, () -> lines.add(END));
        MessageBus.Subscription subscription = runtime.dispatcher().subscribe(trace, JobEvent.class);
        JobHandle<DemoAnswer> handle = runtime.dispatcher().submit(agent);
        Thread.ofVirtual().name("agent-trace-" + agent.getId()).start(() -> {
            try {
                Throwable settled = null;
                try {
                    handle.get();
                }
                catch (ExecutionException e) {
                    settled = e.getCause() != null ? e.getCause() : e;
                }
                if (!trace.awaitEnd(TERMINAL_EVENT_WAIT)) {
                    trace.fail(settled != null ? settled
                            : new IllegalStateException("The run settled without its terminal event reaching the stream"));
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                trace.fail(e);
            }
            finally {
                subscription.unsubscribe();
            }
        });
        return Response.ok(stream(lines)).build();
    }

    /**
     * The benchmark, streamed as it runs: the demo agent wrapped in the runtime's {@link Benchmark},
     * one query answered on the strongest model the deployment serves and then on every open model
     * of every rung, judged blind, through the engine's {@link BenchmarkRace}. Refused before the
     * stream opens when no model can race, since that race could only fail.
     */
    @POST
    @Path("benchmark")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("application/x-ndjson")
    public Response benchmark(BenchmarkRequest input) {
        if (input.query() == null || input.query().isBlank()) {
            throw refusal(Response.Status.BAD_REQUEST, "A query to run the agent on");
        }
        if (input.runs() == null || input.runs() < 1) {
            throw refusal(Response.Status.BAD_REQUEST, "runs: how many times each model answers, at least 1");
        }
        Identifiable workflow = Job.workflow("anonymous", "benchmark");
        Benchmark<DemoQuery, DemoAnswer> benchmark = new DemoBenchmark(workflow, input.runs());
        List<ModelSpec> raced = new ArrayList<>();
        for (Grade rung : Grade.values()) {
            if (rung.isRung()) {
                raced.addAll(Benchmark.candidates(rung));
            }
        }
        if (raced.isEmpty()) {
            throw refusal(Response.Status.INTERNAL_SERVER_ERROR,
                    "No model to race: a benchmark needs at least one open entry whose provider is configured; provide a credential (POST /connect or the environment) and run the discovery");
        }
        benchmark.setCandidates(raced);
        DemoQuery query = new DemoQuery();
        query.setQuery(input.query());
        benchmark.setInput(query);
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        BenchmarkRace race = new BenchmarkRace(this::toJson, workflow.getWorkflowId(), benchmark.getId(), lines::add, () -> lines.add(END));
        MessageBus.Subscription subscription = runtime.dispatcher().subscribe(race, JobEvent.class);
        race.plan(raced, input.runs());
        JobHandle<DemoAnswer> handle = runtime.dispatcher().submit(benchmark);
        Thread.ofVirtual().name("benchmark-race-" + benchmark.getId()).start(() -> {
            try {
                race.complete(new BenchmarkOutcome(handle.get(), benchmark.report()));
            }
            catch (ExecutionException e) {
                race.fail(e.getCause() != null ? e.getCause() : e);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                race.fail(e);
            }
            finally {
                subscription.unsubscribe();
            }
        });
        return Response.ok(stream(lines)).build();
    }

    /**
     * The container's Jackson mapper as an unchecked serializer, so the traces can name a line
     * without a checked exception. A serialization failure is thrown as a plain runtime exception,
     * not the {@link UncheckedIOException} or {@link IllegalStateException} the trace takes for a
     * reader that left, so a real failure propagates rather than being mistaken for a lost stream.
     */
    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new RuntimeException("Could not serialize a run's line to JSON", e);
        }
    }

    @GET
    @Path("status")
    @Produces(MediaType.APPLICATION_JSON)
    public RuntimeStatus status() {
        return admin.status();
    }

    /**
     * Credentials pasted into the page, held for this process only ({@link SessionCredentials}: a
     * runtime config source, never a file, never echoed back), then tested right then. The shared
     * {@link CatalogAdmin} holds the rules and the answer rides back with the fresh status.
     */
    @POST
    @Path("connect")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ConnectOutcome connect(ConnectRequest input) {
        return mapped(() -> admin.connect(input));
    }

    /**
     * The discovery, on a click instead of a terminal: the shared {@link CatalogAdmin} lists and
     * pings only the providers whose credentials are new or changed this session, writes the result
     * to the deployment's {@code models.json} and adopts it live. The click is the authorization.
     */
    @POST
    @Path("discover")
    @Produces(MediaType.APPLICATION_JSON)
    public DiscoverOutcome discover() {
        return mapped(admin::discover);
    }

    /**
     * One entry of the deployment's own catalog file, edited from the page: its grade, its status
     * (OPEN or DISABLED), its input or output price, or a confirmation of its inferred facts; a
     * null leaves the field alone. A refused edit is a 400; an edit with no file of the
     * deployment's own to land in is a 409.
     */
    @POST
    @Path("catalog/entry")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public RuntimeStatus editEntry(EntryEdit edit) {
        return mapped(() -> admin.editEntry(edit));
    }

    /**
     * One pin of the deployment's own catalog file, set or cleared from the page: the embeddings
     * entry or the decision entry.
     */
    @POST
    @Path("catalog/pin")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public RuntimeStatus pin(PinEdit edit) {
        return mapped(() -> admin.pin(edit));
    }

    /** One grade's order of the deployment's own catalog file, set from the page or cleared. */
    @POST
    @Path("catalog/order")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public RuntimeStatus order(OrderEdit edit) {
        return mapped(() -> admin.order(edit));
    }

    /**
     * Maps the shared write-side refusals to this framework's status codes, carrying the rule it
     * broke as {@code {"message": ...}} - the shape the Spring host returns and the page reads.
     */
    private static <T> T mapped(java.util.function.Supplier<T> op) {
        try {
            return op.get();
        }
        catch (IllegalArgumentException refused) {
            throw refusal(Response.Status.BAD_REQUEST, refused.getMessage());
        }
        catch (IllegalStateException conflict) {
            throw refusal(Response.Status.CONFLICT, conflict.getMessage());
        }
        catch (UncheckedIOException failed) {
            throw refusal(Response.Status.INTERNAL_SERVER_ERROR, failed.getMessage());
        }
    }

    private static WebApplicationException refusal(Response.Status status, String message) {
        return refusal(status, message, null);
    }

    private static WebApplicationException refusal(Response.Status status, String message, Throwable cause) {
        return new WebApplicationException(cause, Response.status(status).entity(new ErrorBody(message)).type(MediaType.APPLICATION_JSON).build());
    }

    /**
     * A refusal body in the shape the page reads: the rule it broke under {@code message}. It is
     * only ever the entity of a {@link Response} built at runtime, never a declared endpoint type,
     * so Quarkus's build-time scan never sees it; it is registered for reflection here so the native
     * image can serialize it.
     */
    @RegisterForReflection
    public record ErrorBody(String message) {}

    @POST
    @Path("ask")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public QuickAnswer ask(QuickLLMQuestionInput input) {
        QuickLLMQuestionTool tool = new QuickLLMQuestionTool(Job.workflow("anonymous", "ask"));
        tool.setUpstreamRetries(DemoPolicy.UPSTREAM_RETRIES);
        tool.setInput(input);
        try (QuickAnswer.Capture capture = QuickAnswer.capture(runtime.dispatcher(), tool)) {
            return capture.answer(await(runtime.dispatcher().submit(tool)));
        }
    }

    @POST
    @Path("extract")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ExtractReport extract(ExtractRequest input) {
        ExtractDirectoryDoer doer = new ExtractDirectoryDoer(Job.workflow("anonymous", "extract"), runtime.ledger(), index);
        doer.setInput(input);
        return await(runtime.dispatcher().submit(doer));
    }

    @POST
    @Path("search")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public List<FileIndex.Hit> search(SearchRequest input) {
        if (index.size() == 0) {
            throw refusal(Response.Status.CONFLICT, "The index is empty; read a directory first (POST /extract)");
        }
        TextToEmbed toEmbed = new TextToEmbed();
        toEmbed.setKey("query");
        toEmbed.setText(input.query());
        toEmbed.setPurpose(EmbeddingPurpose.QUERY);
        EmbedTextTool tool = new EmbedTextTool(Job.workflow("anonymous", "search"));
        tool.setInput(toEmbed);
        Embedding query = await(runtime.dispatcher().submit(tool));
        return index.search(query, input.k() != null ? input.k() : 5);
    }

    @POST
    @Path("pricing")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public PricingReport pricing(PricingRequest input) {
        if (index.size() == 0) {
            throw refusal(Response.Status.CONFLICT, "The index is empty; read a directory first (POST /extract)");
        }
        ExtractPricingDoer doer = new ExtractPricingDoer(Job.workflow("anonymous", "pricing"), runtime.ledger(), index);
        doer.setInput(input);
        return await(runtime.dispatcher().submit(doer));
    }

    /** The pricing run with its grouping put to a decision model: the same doer, reading tier and report, the names grouped by verdicts. */
    @POST
    @Path("decide-prices")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public PricingReport decidePrices(PricingRequest input) {
        if (index.size() == 0) {
            throw refusal(Response.Status.CONFLICT, "The index is empty; read a directory first (POST /extract)");
        }
        ExtractPricingDoer doer = new ExtractPricingDoer(Job.workflow("anonymous", "decide-prices"), runtime.ledger(), index,
                ExtractPricesTool::new, DecideProductGroupsTool::new);
        doer.setInput(input);
        return await(runtime.dispatcher().submit(doer));
    }

    /** How long the handle's holder waits for the run's own terminal event to end the stream once the handle settled. */
    static final Duration TERMINAL_EVENT_WAIT = Duration.ofSeconds(5);
    /** The line that ends a streamed run's queue. */
    private static final String END = "\u0000";

    @GET
    @Path("decide")
    @Produces(MediaType.APPLICATION_JSON)
    public DecideCapabilities decide() {
        return PriceChangeFinder.capabilities();
    }

    /**
     * The decision agent run, streamed as it happens: the engine's {@link DecisionTrace} writes
     * the lines to a queue, and the response drains the queue until the trace ends it. The
     * stream ends from the run's own terminal event; the handle's holder ends it itself only
     * when that event never reached the stream, as the Spring host does.
     */
    @POST
    @Path("decide")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("application/x-ndjson")
    public Response decide(DecideRequest input) {
        if (input.directory() == null || input.directory().isBlank()) {
            throw refusal(Response.Status.BAD_REQUEST, "A folder to work on: an absolute path on this machine (the shipped corpus's is on the status)");
        }
        Identifiable workflow = Job.workflow("anonymous", "decide");
        PriceChangeFinder finder = new PriceChangeFinder(workflow);
        finder.setInput(new Folder(input.directory()));
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        DecisionTrace trace = new DecisionTrace(workflow.getWorkflowId(), finder.getId(), lines::add, () -> lines.add(END));
        MessageBus.Subscription subscription = runtime.dispatcher().subscribe(trace, JobEvent.class);
        JobHandle<?> handle = runtime.dispatcher().submit(finder);
        Thread.ofVirtual().name("decision-trace-" + finder.getId()).start(() -> {
            try {
                Throwable settled = null;
                try {
                    handle.get();
                }
                catch (ExecutionException e) {
                    settled = e.getCause() != null ? e.getCause() : e;
                }
                if (!trace.awaitEnd(TERMINAL_EVENT_WAIT)) {
                    trace.fail(settled != null ? settled
                            : new IllegalStateException("The run settled without its terminal event reaching the stream"));
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                trace.fail(e);
            }
            finally {
                subscription.unsubscribe();
            }
        });
        return Response.ok(stream(lines)).build();
    }

    /** Drains a streamed run's line queue to the response until the trace's END sentinel closes it. */
    private static StreamingOutput stream(BlockingQueue<String> lines) {
        return out -> {
            try {
                for (String line = lines.take(); !END.equals(line); line = lines.take()) {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while streaming the run", e);
            }
        };
    }

    private static <T> T await(JobHandle<T> handle) {
        try {
            return handle.get();
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw refusal(Response.Status.INTERNAL_SERVER_ERROR, cause.getMessage(), cause);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw refusal(Response.Status.SERVICE_UNAVAILABLE, "interrupted while waiting for the job", e);
        }
    }

    public record SearchRequest(String query, Integer k) {}
}
