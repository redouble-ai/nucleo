/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models.discovery;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The classification call runs as a {@link ProbeCall} on the classifying model through the
 * dispatcher, at STANDARD depth with the 2000-token answer budget and the six-minute cap as
 * its timeout: the model's text comes back as the answer, a call past the cap is raised as
 * no classification within it, and any other failure of the call is the failure it is,
 * raised to the discovery with no retry spent on it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-13)
 */
class ModelClassifierCallTest {

    @BeforeAll
    static void boot() {
        JobDispatcher.getInstance().start();
    }

    /** Answers, fails, or sleeps past any cap; captures the job it was called from and what the call sent. */
    static final class ProviderClient extends AbstractLLMClient<String> {
        private final String answer;
        private final RuntimeException failure;
        private final boolean sleeps;
        int calls;
        Job<?> executing;
        Depth depth;
        OutputDeclaration output;

        ProviderClient(ModelSpec model, String answer, RuntimeException failure) {
            this(model, answer, failure, false);
        }

        ProviderClient(ModelSpec model, String answer, RuntimeException failure, boolean sleeps) {
            this.answer = answer;
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
            response.getResponseMessage().overwriteRawContent(answer);
            return response;
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

    private static StandardModelSpec classifier(String id) {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId(id);
        spec.setIdentity(id);
        spec.setProviderKey("stub");
        spec.setWireModelId(id);
        spec.setGrade(Grade.XL);
        spec.setMaxContextTokens(200_000);
        spec.setMaxOutputTokens(16_000);
        spec.setTpm(1_000_000);
        spec.setRpm(10_000);
        return spec;
    }

    @Test
    void theClassifyingModelsTextIsTheAnswer() throws Exception {
        StandardModelSpec spec = classifier("classifier-answers");
        ProviderClient client = new ProviderClient(spec, "{\"entries\": []}", null);
        assertEquals("{\"entries\": []}", ModelClassifier.call(new StubProvider(client), spec, "classify this listing"));
        assertEquals(1, client.calls);
    }

    @Test
    void theCallIsAProbeCallAtStandardDepth_withTheAnswerBudgetAndTheSixMinuteCap() throws Exception {
        StandardModelSpec spec = classifier("classifier-shape");
        ProviderClient client = new ProviderClient(spec, "{\"entries\": []}", null);
        ModelClassifier.call(new StubProvider(client), spec, "classify this listing");
        assertTrue(client.executing instanceof ProbeCall, "the classification call is a probe call");
        assertSame(spec, ((ProbeCall) client.executing).pinnedModel(), "pinned to the classifying model");
        assertEquals(0, client.executing.getUpstreamRetries(), "one call, no transparent retries");
        assertEquals(Duration.ofMinutes(6), ModelClassifier.CAP, "the cap is six minutes");
        assertEquals(ModelClassifier.CAP, client.executing.getTimeout(), "and the cap is the call's timeout");
        assertEquals(Depth.STANDARD, client.depth, "the judgment rides the thinking channel");
        assertEquals(OutputDeclaration.of(ModelClassifier.OUTPUT_BUDGET), client.output, "one small entry of an answer");
        assertEquals(2_000, ModelClassifier.OUTPUT_BUDGET);
    }

    @Test
    void aCallPastTheCapIsRaisedAsNoClassificationWithinIt() {
        StandardModelSpec spec = classifier("classifier-slow");
        ProviderClient client = new ProviderClient(spec, "never", null, true);
        UncorrectableRuntimeLLMException raised = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ModelClassifier.call(new StubProvider(client), spec, "classify this listing", Duration.ofSeconds(1)),
                "a call that cannot answer within the cap is the answer");
        assertEquals("No classification from classifier-slow within 1s", raised.getMessage());
    }

    @Test
    void aFailedCallIsRaisedAsItself_withNoRetrySpent() {
        StandardModelSpec spec = classifier("classifier-fails");
        UncorrectableRuntimeLLMException failure = new UncorrectableRuntimeLLMException("the provider answered HTTP 400");
        ProviderClient client = new ProviderClient(spec, null, failure);
        Exception raised = assertThrows(Exception.class, () -> ModelClassifier.call(new StubProvider(client), spec, "classify this listing"));
        assertSame(failure, raised, "the failure is raised as itself, never wrapped");
        assertEquals(1, client.calls, "one call, no transparent retry");
    }
}
