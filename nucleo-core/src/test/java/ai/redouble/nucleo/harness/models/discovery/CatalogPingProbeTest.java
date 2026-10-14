/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models.discovery;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A chat entry's ping runs as a {@link ProbeCall} through the dispatcher, at IMMEDIATE depth
 * with the 2048-token headroom and the two-minute cap as its timeout: a reachable entry's
 * answer is harvested onto the outcome with the provider's envelope, an entry that cannot
 * answer within the cap is down, and a failing one is a FAILED outcome classified by the
 * signal the provider raised, read through the dispatcher's wrapping, with no retry spent on
 * it. The client is the entry's provider's own, built for the call and closed after it. An
 * embeddings or a decision entry, whose client is not a conversation, is called once straight
 * from its client under the same cap, and its failure is classified and reported by the first
 * signal in its cause chain exactly as a chat entry's is. A ping interrupted while it waits is
 * a FAILED outcome classified OTHER, and the interrupt is raised again.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-13)
 */
class CatalogPingProbeTest {

    @BeforeAll
    static void boot() {
        JobDispatcher.getInstance().start();
    }

    /** An embeddings client that embeds, raises the given failure, or sleeps past any cap. */
    static final class StubEmbeddings implements EmbeddingsClient {
        private ModelSpec model;
        private final boolean sleeps;
        private final RuntimeException failure;
        int calls;

        StubEmbeddings(ModelSpec model, boolean sleeps) {
            this(model, sleeps, null);
        }

        StubEmbeddings(ModelSpec model, boolean sleeps, RuntimeException failure) {
            this.model = model;
            this.sleeps = sleeps;
            this.failure = failure;
        }

        @Override
        public EmbeddingsResponse embed(String input, EmbeddingPurpose purpose) throws InterruptedException {
            calls++;
            if (sleeps) {
                Thread.sleep(Duration.ofSeconds(30));
            }
            if (failure != null) {
                throw failure;
            }
            EmbeddingsResponse response = new EmbeddingsResponse(model, purpose);
            response.setVector(new float[] {0.1f, 0.2f});
            return response;
        }

        @Override
        public ModelSpec getModel() {
            return model;
        }

        @Override
        public void setModel(ModelSpec model) {
            this.model = model;
        }
    }

    /** A decision client that answers the ping's one question. */
    static final class StubDecision implements DecisionClient {
        private ModelSpec model;
        int calls;

        StubDecision(ModelSpec model) {
            this.model = model;
        }

        @Override
        public DecisionResponse decide(DecisionRequest request) {
            calls++;
            DecisionResponse response = new DecisionResponse(model, request);
            response.setSuccessful(true);
            response.setServedModelId("served-" + model.getId());
            return response;
        }

        @Override
        public ModelSpec getModel() {
            return model;
        }

        @Override
        public void setModel(ModelSpec model) {
            this.model = model;
        }
    }

    @Test
    void anEmbeddingsEntryIsPingedStraightFromItsClient_underTheSameCap() {
        StandardModelSpec spec = entry("ping-embeddings");
        spec.setProviderKey("stub-embeddings");
        assertTrue(spec.isEmbeddings());
        StubEmbeddings embeddings = new StubEmbeddings(spec, false);
        ProbeOutcome outcome = CatalogPing.ping(new StubProvider(embeddings), spec);
        assertEquals(ProbeOutcome.Status.OK, outcome.getStatus(), "one embedding of one word, answered");
        assertEquals(1, embeddings.calls);
        StubEmbeddings slow = new StubEmbeddings(spec, true);
        ProbeOutcome late = CatalogPing.ping(new StubProvider(slow), spec, Duration.ofSeconds(1));
        assertEquals(ProbeOutcome.Status.FAILED, late.getStatus());
        assertEquals(ProbeOutcome.Classification.AVAILABILITY, late.getClassification(), "the cap applies to an embeddings entry too");
        assertEquals("no answer within 1s", late.getErrorMessage());
    }

    @Test
    void anEmbeddingsEntrysFailureIsReportedAsTheSignalInsideTheClientsWrapper() {
        StandardModelSpec spec = entry("ping-embeddings-refused");
        spec.setProviderKey("stub-embeddings");
        UnauthorizedException refused = new UnauthorizedException("stub", "invalid api key");
        StubEmbeddings failing = new StubEmbeddings(spec, false,
                new UncorrectableRuntimeLLMException("the embeddings endpoint answered HTTP 401", refused));
        ProbeOutcome outcome = CatalogPing.ping(new StubProvider(failing), spec);
        assertEquals(ProbeOutcome.Status.FAILED, outcome.getStatus());
        assertEquals(ProbeOutcome.Classification.AUTH, outcome.getClassification(), "a rejected credential is a config incident, read through the client's wrapper");
        assertEquals("UnauthorizedException", outcome.getErrorClass(), "reported as the signal, never as the wrapper");
        assertTrue(outcome.getErrorMessage().startsWith("the embeddings endpoint answered HTTP 401"), "the whole account, outermost first: " + outcome.getErrorMessage());
        assertEquals(1, failing.calls, "one call, no retry");
    }

    @Test
    void aDecisionEntryIsPingedStraightFromItsClient() {
        StandardModelSpec spec = entry("ping-decision");
        spec.setProviderKey("stub-decision");
        assertTrue(spec.isDecision());
        StubDecision decision = new StubDecision(spec);
        ProbeOutcome outcome = CatalogPing.ping(new StubProvider(decision), spec);
        assertEquals(ProbeOutcome.Status.OK, outcome.getStatus(), "one question, answered");
        assertEquals("served-ping-decision", outcome.getServedModelId(), "the served model rides the outcome");
        assertEquals(1, decision.calls);
    }

    /**
     * A client that answers once, raises the given signal on every call, or sleeps past any
     * cap; counts its calls and captures the job it was called from and what the call sent.
     */
    static final class ProviderClient extends AbstractLLMClient<String> {
        private final RuntimeException failure;
        private final boolean sleeps;
        int calls;
        boolean closed;
        Job<?> executing;
        Depth depth;
        OutputDeclaration output;
        String prompt;

        ProviderClient(ModelSpec model, RuntimeException failure) {
            this(model, failure, false);
        }

        ProviderClient(ModelSpec model, RuntimeException failure, boolean sleeps) {
            this.failure = failure;
            this.sleeps = sleeps;
            setModel(model);
        }

        @Override
        public APIDialect getDialect() {
            return APIDialect.BEDROCK_CONVERSE;
        }

        @Override
        protected TextWrapper<String> textWrapper() {
            return text -> text;
        }

        @Override
        protected <T> LLMResponse<T> doSingleResponse(LLMRequest<T> request, PreparedConversation prepared) {
            calls++;
            executing = JobContext.getCurrentExecution().getJob();
            depth = request.getContext().resolveDepth();
            output = request.getContext().getOutputDeclaration();
            prompt = request.getContext().getLastOutgoingMessage().getRawContent();
            if (sleeps) {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while answering", e);
                }
            }
            if (failure != null) {
                throw failure;
            }
            LLMResponse<T> response = new LLMResponse<>(request);
            response.setStopReason(LLMStopReason.END_TURN);
            response.setServedModelId("served-" + getModel().getId());
            response.setProviderRequestId("req-1");
            response.setRateLimitInfo(new RateLimitInfo(getModel().getId(), 40000, 39990, null, 500, 499, null, false));
            response.getResponseMessage().overwriteRawContent("pong");
            return response;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        protected boolean is429Error(Exception e) {return false;}

        @Override
        protected boolean isServerError(Exception e) {return false;}

        @Override
        protected boolean isOverloadError(Exception e) {return false;}

        @Override
        protected RateLimitInfo extractRateLimitInfo(Exception e) {return null;}
    }

    static final class StubProvider implements ClientProvider<Client> {
        private final Client client;

        StubProvider(Client client) {
            this.client = client;
        }

        @Override
        public String key() {
            return "stub";
        }

        @Override
        public Client createClient(ModelSpec spec) {
            return client;
        }

        @Override
        public String credentialId() {
            return "stub-key";
        }

        @Override
        public String platform() {
            return "stub";
        }
    }

    private static StandardModelSpec entry(String id) {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId(id);
        spec.setIdentity(id);
        spec.setProviderKey("stub");
        spec.setWireModelId(id);
        spec.setGrade(Grade.SMALL);
        spec.setMaxContextTokens(100_000);
        spec.setMaxOutputTokens(8_000);
        spec.setTpm(1_000_000);
        spec.setRpm(10_000);
        return spec;
    }

    @Test
    void aReachableEntrysAnswerIsHarvestedWithTheProvidersEnvelope() {
        StandardModelSpec spec = entry("ping-reachable");
        ProviderClient client = new ProviderClient(spec, null);
        ProbeOutcome outcome = CatalogPing.ping(new StubProvider(client), spec);
        assertEquals(ProbeOutcome.Status.OK, outcome.getStatus(), "the entry answered");
        assertEquals("served-ping-reachable", outcome.getServedModelId(), "the served model rides the outcome");
        assertEquals("req-1", outcome.getProviderRequestId());
        assertEquals(40000L, outcome.getObservedTokensLimit(), "the account's limits ride the outcome");
        assertEquals(500L, outcome.getObservedRequestsLimit());
        assertEquals(1, client.calls, "one call");
        assertTrue(client.closed, "the client built for the call is closed after it");
        assertNotNull(outcome.getLatencyMs());
    }

    @Test
    void aChatPingIsAProbeCallAtImmediateDepth_withTheHeadroomAndTheTwoMinuteCap() {
        StandardModelSpec spec = entry("ping-shape");
        ProviderClient client = new ProviderClient(spec, null);
        CatalogPing.ping(new StubProvider(client), spec);
        assertTrue(client.executing instanceof ProbeCall, "a chat entry's ping is a probe call");
        assertSame(spec, ((ProbeCall) client.executing).pinnedModel(), "pinned to the entry under probe");
        assertEquals(0, client.executing.getUpstreamRetries(), "one call, no transparent retries");
        assertEquals(Duration.ofMinutes(2), CatalogPing.PING_CAP, "two minutes is the cap");
        assertEquals(CatalogPing.PING_CAP, client.executing.getTimeout(), "and the cap is the call's timeout");
        assertEquals(Depth.IMMEDIATE, client.depth, "a one-word answer needs no thinking");
        assertEquals(OutputDeclaration.of(CatalogPing.PING_OUTPUT_BUDGET), client.output, "headroom for a reasoning model's preamble");
        assertEquals(2048, CatalogPing.PING_OUTPUT_BUDGET);
        assertEquals(CatalogPing.PING_PROMPT, client.prompt, "the one-word prompt");
    }

    @Test
    void anEntryThatCannotAnswerWithinTheCapIsDown() {
        StandardModelSpec spec = entry("ping-slow");
        ProviderClient client = new ProviderClient(spec, null, true);
        ProbeOutcome outcome = CatalogPing.ping(new StubProvider(client), spec, Duration.ofSeconds(1));
        assertEquals(ProbeOutcome.Status.FAILED, outcome.getStatus());
        assertEquals(ProbeOutcome.Classification.AVAILABILITY, outcome.getClassification(), "a call that cannot answer within the cap IS the answer");
        assertEquals("TimeoutException", outcome.getErrorClass());
        assertEquals("no answer within 1s", outcome.getErrorMessage());
    }

    @Test
    void aPingInterruptedWhileItWaitsIsOther_andTheInterruptIsRaisedAgain() {
        StandardModelSpec spec = entry("ping-interrupted");
        ProviderClient client = new ProviderClient(spec, null, true);
        Thread.currentThread().interrupt();
        ProbeOutcome outcome;
        try {
            outcome = CatalogPing.ping(new StubProvider(client), spec, Duration.ofSeconds(1));
        }
        finally {
            assertTrue(Thread.interrupted(), "the interrupt is raised again for whoever owns the thread");
        }
        assertEquals(ProbeOutcome.Status.FAILED, outcome.getStatus());
        assertEquals(ProbeOutcome.Classification.OTHER, outcome.getClassification(), "an interruption says nothing about the entry");
        assertEquals("InterruptedException", outcome.getErrorClass());
        assertEquals("interrupted", outcome.getErrorMessage());
    }

    @Test
    void aBusyEntryIsAFailedOutcomeClassifiedBusy_withNoRetrySpent() {
        StandardModelSpec spec = entry("ping-busy");
        ProviderClient client = new ProviderClient(spec,
                new RateLimitRetryException("429", null, 1, "rate limited", Duration.ofSeconds(5), null));
        ProbeOutcome outcome = CatalogPing.ping(new StubProvider(client), spec);
        assertEquals(ProbeOutcome.Status.FAILED, outcome.getStatus());
        assertEquals(ProbeOutcome.Classification.THROTTLE, outcome.getClassification(), "busy is not down, read through the dispatcher's wrapping");
        assertEquals("RateLimitRetryException", outcome.getErrorClass(), "reported as the signal the provider raised");
        assertTrue(outcome.getErrorMessage().contains("RateLimitRetryException"), "the account names the signal: " + outcome.getErrorMessage());
        assertEquals(1, client.calls, "a probe asks whether the call succeeds NOW: no transparent retry");
        assertTrue(client.closed, "the client is closed on failure too");
    }
}
