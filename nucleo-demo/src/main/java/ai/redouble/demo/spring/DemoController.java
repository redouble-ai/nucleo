/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.spring;

import ai.redouble.demo.*;
import ai.redouble.demo.decide.*;
import ai.redouble.demo.extract.*;
import ai.redouble.demo.pricing.*;
import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.prompt.skill.*;
import ai.redouble.nucleo.secrets.*;
import ai.redouble.nucleo.spring.*;
import ai.redouble.nucleo.tools.benchmark.*;
import ai.redouble.nucleo.tools.builtin.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.*;
import org.springframework.web.servlet.mvc.method.annotation.*;
import tools.jackson.databind.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The doors of the demo, which the page at {@code /} ({@code static/index.html}) walks a
 * person through. {@code GET /status} reports what this process can do: whether the
 * dispatcher runs, which providers are on the classpath and which of them hold a credential,
 * the catalog file it reads or the steps to write one, and the entries it loaded. {@code POST /ask} submits one job, the runtime's own
 * quick-question tool, under a workflow owned by the caller, waits for it and returns the
 * tool's output; a refusal (no provider configured for the grade asked, a missing credential)
 * comes back with the runtime's own message, which says what to provide. {@code GET /skills}
 * lists every skill the loader found on the classpath, with the resource origin each declared.
 * {@code GET /agent} shows the demo agent's palette and skill catalog as its model would see
 * them, and {@code POST /agent} runs it on a query.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
@RestController
public class DemoController {
    /**
     * How long the handle's holder waits for the run's own terminal event to end the stream
     * once the handle settled. The dispatcher publishes the event before it settles the
     * handle and the bus delivers in order, so the wait covers the bus's delivery alone.
     */
    static final Duration TERMINAL_EVENT_WAIT = Duration.ofSeconds(5);
    private final NucleoRuntime runtime;
    private final FileIndex index;
    private final CatalogAdmin admin;
    /** Boot's own mapper, so a report written to a file has the shape the same report has on the wire. */
    private final ObjectMapper json;

    public DemoController(NucleoRuntime runtime, FileIndex index, CatalogAdmin admin, ObjectMapper json) {
        this.runtime = runtime;
        this.index = index;
        this.admin = admin;
        this.json = json;
    }

    /**
     * The embedded documentation lives under {@code META-INF/resources/docs}, generated into
     * the engine jar from the reactor's own markdown at build time; static serving has no
     * directory index, so the bare path forwards to the site's front page.
     */
    @GetMapping("/docs")
    public ResponseEntity<Void> docs() {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create("/docs/index.html"));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    @GetMapping("/skills")
    public List<SkillEntry> skills() {
        return DemoApi.skills();
    }

    @GetMapping("/agent")
    public AgentCapabilities agent(Principal principal) {
        return DemoApi.agentCapabilities(user(principal));
    }

    /**
     * The agent run, streamed as it happens: one JSON line per event of the run and of every
     * job under it through {@link AgentTrace} - the model turns with what they decided and
     * cost, the tool calls with their arguments and results, the notes between them - ending
     * in the answer with the run's totals, or the failure. The grade is the caller's: the
     * agent declares MEDIUM, and a request naming a rung runs it there instead, the way the
     * benchmark re-aims the same agent per candidate.
     */
    @PostMapping(value = "/agent", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public ResponseBodyEmitter agent(@RequestBody AgentRequest input, Principal principal) {
        if (input.query() == null || input.query().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A query to run the agent on");
        }
        Identifiable workflow = Job.workflow(user(principal), "agent");
        DemoAgent agent = new DemoAgent(workflow);
        DemoQuery query = new DemoQuery();
        query.setQuery(input.query());
        agent.setInput(query);
        if (input.grade() != null) {
            agent.setGrade(input.grade());
        }
        // subscribed before the submit, so the first scheduling events are already on the wire
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        AgentTrace trace = new AgentTrace(json::writeValueAsString, workflow.getWorkflowId(), agent.getId(), line -> {
            try {
                emitter.send(line, MediaType.APPLICATION_NDJSON);
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, emitter::complete);
        MessageBus.Subscription subscription = runtime.dispatcher().subscribe(trace, JobEvent.class);
        JobHandle<DemoAnswer> handle = runtime.dispatcher().submit(agent);
        // the run's own terminal event ends the stream, from the trace, after every line before
        // it; the handle's holder only waits for that end and unsubscribes, and ends the stream
        // itself only when the run settled without its terminal event ever arriving
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
        return emitter;
    }

    /**
     * The workflow's owner is the caller. This process has no login, so the container hands
     * it no principal; a host with one puts its authenticated user here and never a constant.
     */
    private static String user(Principal principal) {
        return principal != null ? principal.getName() : "anonymous";
    }

    /**
     * What the page opens with: whether the dispatcher runs, every provider with whether its
     * credential is present and how to provide it, the catalog file this process reads (or
     * the steps to write one), every entry it loaded, the shipped corpus's absolute path
     * on this machine, located by {@link DemoCorpus} wherever the process runs, and the day
     * the corpus's price story is answered for, which the pricing steps open on.
     */
    @GetMapping("/status")
    public RuntimeStatus status() {
        return admin.status();
    }

    /** Maps the shared write-side refusals to this framework's status codes. */
    private static <T> T mapped(java.util.function.Supplier<T> op) {
        try {
            return op.get();
        }
        catch (IllegalArgumentException refused) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, refused.getMessage(), refused);
        }
        catch (IllegalStateException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage(), conflict);
        }
        catch (UncheckedIOException failed) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, failed.getMessage(), failed);
        }
    }

    /**
     * Credentials pasted into the page, held for this process only ({@link SessionCredentials}:
     * a runtime property source, never a file, never echoed back), then tested right then. The
     * shared {@link CatalogAdmin} holds the rules - a deployment-configured credential is refused
     * rather than shadowed, a session credential may be corrected - and the answer rides back
     * with the fresh status.
     */
    @PostMapping("/connect")
    public ConnectOutcome connect(@RequestBody ConnectRequest input) {
        return mapped(() -> admin.connect(input));
    }

    /**
     * The discovery, on a click instead of a terminal: the shared {@link CatalogAdmin} lists and
     * pings only the providers whose credentials are new or changed this session, writes the
     * result to the deployment's {@code models.json} and adopts it live. The click is the
     * authorization: the pings spend a few cents on the newly connected accounts.
     */
    @PostMapping("/discover")
    public DiscoverOutcome discover() {
        return mapped(admin::discover);
    }

    /**
     * One entry of the deployment's own catalog file, edited from the page: its grade, its status
     * (OPEN or DISABLED), its input or output price, or a confirmation of its inferred facts; a
     * null leaves the field alone. A refused edit is a 400 carrying the rule it broke; an edit
     * with no file of the deployment's own to land in is a 409.
     */
    @PostMapping("/catalog/entry")
    public RuntimeStatus editEntry(@RequestBody EntryEdit edit) {
        return mapped(() -> admin.editEntry(edit));
    }

    /**
     * One pin of the deployment's own catalog file, set or cleared from the page: the embeddings
     * entry or the decision entry.
     */
    @PostMapping("/catalog/pin")
    public RuntimeStatus pin(@RequestBody PinEdit edit) {
        return mapped(() -> admin.pin(edit));
    }

    /** One grade's order of the deployment's own catalog file, set from the page or cleared. */
    @PostMapping("/catalog/order")
    public RuntimeStatus order(@RequestBody OrderEdit edit) {
        return mapped(() -> admin.order(edit));
    }

    @PostMapping("/ask")
    public QuickAnswer ask(@RequestBody QuickLLMQuestionInput input, Principal principal) {
        Identifiable workflow = Job.workflow(user(principal), "ask");
        QuickLLMQuestionTool tool = new QuickLLMQuestionTool(workflow);
        tool.setUpstreamRetries(DemoPolicy.UPSTREAM_RETRIES);
        tool.setInput(input);
        try (QuickAnswer.Capture capture = QuickAnswer.capture(runtime.dispatcher(), tool)) {
            return capture.answer(await(runtime.dispatcher().submit(tool)));
        }
    }

    /**
     * The demo's workflow: every file under a directory through the cheapest tier that reads
     * it, in parallel under admission, indexed, and reported with what each cost. The caller
     * names the directory, a file cap and the run's budgets; a budget stops the run at
     * admission, one file at a time, and the report says which.
     */
    @PostMapping("/extract")
    public ExtractReport extract(@RequestBody ExtractRequest input, Principal principal) {
        ExtractDirectoryDoer doer = new ExtractDirectoryDoer(Job.workflow(user(principal), "extract"), runtime.ledger(), index);
        doer.setInput(input);
        return await(runtime.dispatcher().submit(doer));
    }

    /** The question a person asks the directory afterwards: embedded on the same model as the index, ranked by cosine. */
    @PostMapping("/search")
    public List<FileIndex.Hit> search(@RequestBody SearchRequest input, Principal principal) {
        if (index.size() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The index is empty; read a directory first (step 4 on the page, POST /extract)");
        }
        TextToEmbed toEmbed = new TextToEmbed();
        toEmbed.setKey("query");
        toEmbed.setText(input.query());
        toEmbed.setPurpose(EmbeddingPurpose.QUERY);
        EmbedTextTool tool = new EmbedTextTool(Job.workflow(user(principal), "search"));
        tool.setInput(toEmbed);
        Embedding query = await(runtime.dispatcher().submit(tool));
        return index.search(query, input.k() != null ? input.k() : 5);
    }

    /**
     * The second demo, on the first one's result: every indexed document read for the prices
     * it states, the product names grouped by a model a grade up, and code deciding which
     * price is current and which is superseded. The report is written to the path the caller
     * names, when one is named, and returned either way.
     */
    @PostMapping("/pricing")
    public PricingReport pricing(@RequestBody PricingRequest input, Principal principal) {
        if (index.size() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The index is empty; read a directory first (step 4 on the page, POST /extract)");
        }
        ExtractPricingDoer doer = new ExtractPricingDoer(Job.workflow(user(principal), "pricing"), runtime.ledger(), index);
        doer.setInput(input);
        PricingReport report = await(runtime.dispatcher().submit(doer));
        return written(report, input.getOutput());
    }

    /**
     * The pricing run with its grouping put to a decision model: the same doer, documents,
     * reading tier, reconciliation and report as {@code POST /pricing}, with the distinct
     * product names grouped by {@link DecideProductGroupsTool} on the deployment's decision
     * model in place of the MEDIUM model's one call.
     */
    @PostMapping("/decide-prices")
    public PricingReport decidePrices(@RequestBody PricingRequest input, Principal principal) {
        if (index.size() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The index is empty; read a directory first (step 4 on the page, POST /extract)");
        }
        ExtractPricingDoer doer = new ExtractPricingDoer(Job.workflow(user(principal), "decide-prices"), runtime.ledger(), index,
                ExtractPricesTool::new, DecideProductGroupsTool::new);
        doer.setInput(input);
        PricingReport report = await(runtime.dispatcher().submit(doer));
        return written(report, input.getOutput());
    }

    /** The decision agent's objective and palette, for the page's panel. */
    @GetMapping("/decide")
    public DecideCapabilities decide() {
        return PriceChangeFinder.capabilities();
    }

    /**
     * The decision agent run, streamed as it happens: one JSON line per event of the run and
     * of every job under it through {@link DecisionTrace} - each decision with the state the
     * model was shown, the questions and every distribution it answered, each tool run with
     * the artifact it produced - ending in the statements selected, the run's turns and its
     * totals, or the failure. The stream ends the way the agent's does: from the run's own
     * terminal event, with the handle's holder ending it itself only when that event never
     * reached the stream.
     */
    @PostMapping(value = "/decide", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public ResponseBodyEmitter decide(@RequestBody DecideRequest input, Principal principal) {
        if (input.directory() == null || input.directory().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A folder to work on: an absolute path on this machine (the shipped corpus's is on the status)");
        }
        Identifiable workflow = Job.workflow(user(principal), "decide");
        PriceChangeFinder finder = new PriceChangeFinder(workflow);
        finder.setInput(new Folder(input.directory()));
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        DecisionTrace trace = new DecisionTrace(workflow.getWorkflowId(), finder.getId(), line -> {
            try {
                emitter.send(line, MediaType.APPLICATION_NDJSON);
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, emitter::complete);
        // subscribed before the submit, so the first scheduling events are already on the wire
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
        return emitter;
    }

    /**
     * The benchmark: the demo agent wrapped in the runtime's {@link Benchmark}, so one query
     * runs on the strongest model this deployment serves (the reference, resolved through
     * {@code Grade.CEILING} exactly as the judge is) and then on every open model of every
     * grade this deployment can call, {@code runs} times each, with the strongest model
     * judging the answers blind. The whole ladder races - a cross-grade pin is legal, the
     * gate checks capability and posture, never rung - because the interesting result is
     * exactly the spread: which cheap model holds the reference's score and which strong one
     * is paying for nothing. The response is the race as it runs, one JSON line per event
     * through {@link BenchmarkRace}, ending in the reference run's answer and the report,
     * the same one the runtime logged as a table.
     */
    @PostMapping(value = "/benchmark", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public ResponseBodyEmitter benchmark(@RequestBody BenchmarkRequest input, Principal principal) {
        if (input.query() == null || input.query().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A query to run the agent on");
        }
        if (input.runs() == null || input.runs() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runs: how many times each model answers, at least 1");
        }
        Identifiable workflow = Job.workflow(user(principal), "benchmark");
        Benchmark<DemoQuery, DemoAnswer> benchmark = new DemoBenchmark(workflow, input.runs());
        List<ModelSpec> raced = new ArrayList<>();
        for (Grade rung : Grade.values()) {
            if (rung.isRung()) {
                raced.addAll(Benchmark.candidates(rung));
            }
        }
        // known before a byte is sent: refuse with a status instead of opening a stream that can only fail
        if (raced.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No model to race: a benchmark needs at least one open entry whose provider is configured; provide a credential (POST /connect or the environment) and run the discovery");
        }
        benchmark.setCandidates(raced);
        DemoQuery query = new DemoQuery();
        query.setQuery(input.query());
        benchmark.setInput(query);
        // subscribed before the submit, so the first scheduling events are already on the wire
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        BenchmarkRace race = new BenchmarkRace(json::writeValueAsString, workflow.getWorkflowId(), benchmark.getId(), line -> {
            try {
                emitter.send(line, MediaType.APPLICATION_NDJSON);
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, emitter::complete);
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
        return emitter;
    }

    /** The pricing report written where the caller asked, with Boot's own mapper so the file is the response. */
    private PricingReport written(PricingReport report, String outputPath) {
        if (outputPath != null && !outputPath.isBlank()) {
            Path output = Path.of(outputPath).toAbsolutePath();
            report.setOutputFile(output.toString());
            try {
                Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
            }
            catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The report was produced but could not be written to " + output + ": " + e.getMessage(), e);
            }
        }
        return report;
    }

    private static <T> T await(JobHandle<T> handle) {
        try {
            return handle.get();
        }
        catch (ExecutionException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getCause().getMessage(), e.getCause());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted while waiting for the job", e);
        }
    }

    public record SearchRequest(String query, Integer k) {}
}
