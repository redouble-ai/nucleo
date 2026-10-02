/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ObservableLLMClient} and {@link ObservableEmbeddingsClient} record every call on the
 * job's context: a delegate's response as it is, and a failure as a response marked
 * unsuccessful with the reason, the cause and the model, a truncation labelled with the
 * {@code MAX_TOKENS} stop reason so it is queryable as one.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class ObservableClientsTest {

    /** An embeddings delegate that answers a vector, or throws what the test hands it. */
    static class ScriptedEmbeddingsClient implements EmbeddingsClient {
        private final RuntimeException failure;
        private ModelSpec model = TestModels.small();

        ScriptedEmbeddingsClient(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public EmbeddingsResponse embed(String input, EmbeddingPurpose purpose) {
            if (failure != null) {
                throw failure;
            }
            EmbeddingsResponse response = new EmbeddingsResponse(model, purpose);
            response.setVector(new float[] {0.5f, 0.25f});
            response.setSuccessful(true);
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
    void anEmbeddingsResponseIsRecordedOnTheContextAsItIs() throws Exception {
        JobContext<String> context = context();
        ObservableEmbeddingsClient client = new ObservableEmbeddingsClient(new ScriptedEmbeddingsClient(null), context);
        EmbeddingsResponse response = client.embed("text", EmbeddingPurpose.DOCUMENT);
        assertTrue(response.isSuccessful());
        assertEquals(2, response.getVector().length);
        assertEquals(1, context.getLlmResponses().size(), "every embeddings call is captured");
        assertSame(response, context.getLlmResponses().get(0), "the delegate's own response object");
        assertEquals(TestModels.small().getId(), client.getModel().getId(), "the wrapper answers for the delegate");
    }

    @Test
    void anEmbeddingsFailureIsRecordedAsAnUnsuccessfulResponseWithItsCauseAndModel() {
        JobContext<String> context = context();
        IllegalStateException boom = new IllegalStateException("embeddings down");
        ObservableEmbeddingsClient client = new ObservableEmbeddingsClient(new ScriptedEmbeddingsClient(boom), context);
        assertSame(boom, assertThrows(IllegalStateException.class, () -> client.embed("text", EmbeddingPurpose.QUERY)), "the failure propagates as itself");
        assertEquals(1, context.getLlmResponses().size(), "a call that produced no response is still captured");
        LLMResponse<?> failed = context.getLlmResponses().get(0);
        assertInstanceOf(EmbeddingsResponse.class, failed);
        assertEquals(EmbeddingPurpose.QUERY, ((EmbeddingsResponse) failed).getPurpose(), "under the purpose it was asked for");
        assertFalse(failed.isSuccessful());
        assertEquals("embeddings down", failed.getReasonForFailure());
        assertSame(boom, failed.getLastError());
        assertEquals(TestModels.small().getId(), failed.getModel(), "the delegate's model, so the ledger can price the failed call");
        assertNotNull(failed.getStartTime());
        assertNotNull(failed.getEndTime());
    }
    private static final Identifiable ROOT = Job.workflow("observable-user", "observable-clients-test");

    static class NoOpJob extends AbstractJob<String> {
        NoOpJob() {
            super(ROOT, "observable-fixture");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "x";
        }
    }

    /** A delegate that answers, or throws, what the test hands it. */
    static class ScriptedClient implements LLMClient {
        private final RuntimeException failure;

        ScriptedClient(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public <T> LLMResponse<T> singleResponse(LLMRequest<T> request) {
            if (failure != null) {
                throw failure;
            }
            LLMResponse<T> response = new LLMResponse<>(request);
            response.setSuccessful(true);
            return response;
        }

        @Override
        public Double getTemperature() {
            return null;
        }

        @Override
        public void setTemperature(Double temperature) {
        }

        @Override
        public String getModelIdentifier() {
            return "scripted-model";
        }

        @Override
        public <T> OutgoingMessage<T> createOutgoingMessage(ResponseHandler<T> responseHandler) {
            return null;
        }

        @Override
        public ContentFormatter getFormatter() {
            return null;
        }

        @Override
        public APIDialect getDialect() {
            return null;
        }

        @Override
        public ModelSpec getModel() {
            return null;
        }

        @Override
        public void setModel(ModelSpec model) {
        }
    }

    private static JobContext<String> context() {
        return new JobContext<>(new NoOpJob(), "observable-user", null, new JobRequirements());
    }

    /** A request over a conversation with one outgoing question, the least a response can be created from. */
    private static LLMRequest<String> request() {
        ConversationContext conversation = new ConversationContext();
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText("question");
        conversation.getMessages().add(message);
        return new LLMRequest<>(conversation);
    }

    @Test
    void aResponseIsRecordedOnTheContextAsItIs() throws Exception {
        JobContext<String> context = context();
        ObservableLLMClient client = new ObservableLLMClient(new ScriptedClient(null), context);
        LLMRequest<String> request = request();
        LLMResponse<String> response = client.singleResponse(request);
        assertTrue(response.isSuccessful());
        assertEquals(1, context.getLlmResponses().size(), "every call is captured");
        assertSame(response, context.getLlmResponses().get(0), "the delegate's own response object");
        assertEquals("scripted-model", client.getModelIdentifier(), "the wrapper answers for the delegate");
    }

    @Test
    void aFailureIsRecordedAsAnUnsuccessfulResponseWithItsCause() {
        JobContext<String> context = context();
        IllegalStateException boom = new IllegalStateException("provider down");
        ObservableLLMClient client = new ObservableLLMClient(new ScriptedClient(boom), context);
        LLMRequest<String> request = request();
        assertSame(boom, assertThrows(IllegalStateException.class, () -> client.singleResponse(request)), "the failure propagates as itself");
        assertEquals(1, context.getLlmResponses().size(), "a call that produced no response is still captured");
        LLMResponse<?> failed = context.getLlmResponses().get(0);
        assertFalse(failed.isSuccessful());
        assertEquals("provider down", failed.getReasonForFailure());
        assertSame(boom, failed.getLastError());
        assertEquals("scripted-model", failed.getModel());
        assertNotNull(failed.getStartTime());
        assertNotNull(failed.getEndTime());
    }

    @Test
    void aTruncationIsLabelledWithTheMaxTokensStopReason() {
        JobContext<String> context = context();
        OutputTruncationRetryException truncated = new OutputTruncationRetryException("scripted-model", 1000, 4000);
        ObservableLLMClient client = new ObservableLLMClient(new ScriptedClient(truncated), context);
        assertThrows(OutputTruncationRetryException.class, () -> client.singleResponse(request()));
        assertEquals(LLMStopReason.MAX_TOKENS, context.getLlmResponses().get(0).getStopReason(),
                "a truncation retry IS the max_tokens stop reason, recorded as such rather than as unknown");
        context.clearCompletionData();
        assertTrue(context.getLlmResponses().isEmpty(), "clearCompletionData releases the captured responses");
    }

    @Test
    void streamingIsCapturedThroughTheSameWrapper() throws Exception {
        JobContext<String> context = context();
        ObservableLLMClient client = new ObservableLLMClient(new ScriptedClient(null) {
            @Override
            public <T> LLMResponse<T> streamResponse(LLMRequest<T> request, Consumer<StreamChunk> chunkHandler) {
                LLMResponse<T> response = new LLMResponse<>(request);
                response.setSuccessful(true);
                return response;
            }
        }, context);
        LLMResponse<String> response = client.streamResponse(request(), chunk -> { });
        assertNotNull(response.getStartTime(), "a streamed response without a start time gets the call's start");
        assertEquals(1, context.getLlmResponses().size());
    }
}
