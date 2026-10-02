/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the promises {@code AbstractLLMClient}'s templates make regardless of provider:
 * the pre-flight estimate gate refuses an oversized request before the provider is called, the
 * single and streaming templates stamp the same per-response facts (catalog model id, provider
 * key, requested ceiling, success), the streaming template runs the same truncation gate as the
 * single path, a stream refused before its first chunk rides the same typed failure signals as
 * the single path, a stream that fails after delivering a chunk feeds the throttle but never
 * retries, a client without a streaming path refuses in the framework's exception language,
 * the depth-to-reasoning-effort vocabulary is fixed, and the per-block encoder machinery builds
 * each encoder once per client and fails loud on a block type whose mapping is missing.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
public class AbstractLLMClientTemplateContractsTest {

    /** The output every conversation in these tests declares. */
    private static final int TEST_DEFAULT = 16_000;

    /** A client-internal conversation whose seat declares no reasoning and a {@link #TEST_DEFAULT} answer. */
    private static ConversationContext declared(ModelSpec model, String userText) {
        ConversationContext conversation = TestModels.conversation(model);
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(TEST_DEFAULT));
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText(userText);
        conversation.getMessages().add(message);
        return conversation;
    }

    @Test
    void estimateAboveTheModelsContextWindow_refusesBeforeTheProviderIsCalled() {
        ModelSpec model = TestModels.micro();
        // Enough characters that any tokenizer's count exceeds the context window
        String oversized = "a ".repeat(model.getMaxContextTokens() * 4);
        ConversationContext conversation = declared(model, oversized);
        TemplateStubClient client = new TemplateStubClient(LLMStopReason.END_TURN);
        client.setModel(model);

        assertThrows(TokenEstimateExceedsLimitException.class,
                () -> client.singleResponse(new LLMRequest<>(conversation)),
                "the pre-flight gate refuses an estimate above the context window");
        assertEquals(0, client.providerCalls.get(),
                "the refusal happens before any provider call is spent");
    }

    @Test
    void singleResponse_carriesTheTemplateStamps() throws TokenEstimateExceedsLimitException {
        ModelSpec model = TestModels.small();
        ConversationContext conversation = declared(model, "question");
        TemplateStubClient client = new TemplateStubClient(LLMStopReason.END_TURN);
        client.setModel(model);

        LLMResponse<String> response = client.singleResponse(new LLMRequest<>(conversation));

        assertStamps(response, client, model, conversation);
    }

    @Test
    void streamResponse_carriesTheSameStampsAsTheSinglePath() throws TokenEstimateExceedsLimitException {
        ModelSpec model = TestModels.small();
        ConversationContext conversation = declared(model, "question");
        StreamingStubClient client = new StreamingStubClient(LLMStopReason.END_TURN);
        client.setModel(model);

        LLMResponse<String> response = client.streamResponse(new LLMRequest<>(conversation), chunk -> {});

        assertStamps(response, client, model, conversation);
    }

    /** The per-response facts both templates own, so no client can forget them. */
    private static void assertStamps(LLMResponse<String> response, AbstractLLMClient<?> client,
            ModelSpec model, ConversationContext conversation) {
        assertEquals(model.getId(), response.getModel(),
                "the response names the catalog id we called, not the provider's echo");
        assertEquals(client.getDialect().providerKey(), response.getProvider(),
                "the provider key comes from the client's dialect");
        assertEquals(conversation.outputReserve(model), response.getRequestedMaxTokens(),
                "the requested ceiling is the one formula both the wire and the reservation use");
        assertTrue(response.isSuccessful(), "the template marks the completed call successful");
        assertNotNull(response.getEndTime(), "the template stamps the end time");
    }

    @Test
    void streamedTruncation_signalsTheSameRetryAsTheSinglePath() {
        ModelSpec model = TestModels.small();
        assertTrue(model.getMaxOutputTokens() > TEST_DEFAULT,
                "fixture assumption: the small tier's ceiling leaves room above the default budget");
        ConversationContext conversation = declared(model, "question");
        StreamingStubClient client = new StreamingStubClient(LLMStopReason.MAX_TOKENS);
        client.setModel(model);

        OutputTruncationRetryException raised = assertThrows(OutputTruncationRetryException.class,
                () -> client.streamResponse(new LLMRequest<>(conversation), chunk -> {}));

        assertEquals(model.getMaxOutputTokens(), raised.getNewBudget(),
                "the streaming template escalates to the model ceiling exactly like the single path");
    }

    @Test
    void aStreamRefusedBeforeTheFirstChunk_ridesTheSameTypedSignalsAsTheSinglePath() {
        ModelSpec model = TestModels.small();
        ConversationContext conversation = declared(model, "question");
        PreStream429Client client = new PreStream429Client();
        client.setModel(model);

        assertThrows(RateLimitRetryException.class,
                () -> client.streamResponse(new LLMRequest<>(conversation), chunk -> {}),
                "a 429 before any chunk classifies into the transparent-retry signal, as on the single path");
    }

    @Test
    void aStreamThatFailsAfterAChunk_feedsTheThrottleButNeverRetries() {
        ModelSpec model = TestModels.small();
        ConversationContext conversation = declared(model, "question");
        MidStreamOverloadClient client = new MidStreamOverloadClient();
        client.setModel(model);
        double throttleBefore = RateLimiterRegistry.getInstance().getRateLimiter(model).getThrottleCoefficient();

        UncorrectableRuntimeLLMException raised = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> client.streamResponse(new LLMRequest<>(conversation), chunk -> {}),
                "no retry signal escapes once a chunk was delivered - a re-run would re-deliver rendered text");

        assertTrue(raised.getLLMMessage().contains("mid-stream"),
                "the failure names the mid-stream position");
        double throttleAfter = RateLimiterRegistry.getInstance().getRateLimiter(model).getThrottleCoefficient();
        assertTrue(throttleAfter > throttleBefore,
                "the capacity signal still feeds the model's throttle even though the call is not retried");
    }

    @Test
    void aStreamThatFailsAfterAChunkWithAnUnrecognizedFault_propagatesAsThrown() {
        ModelSpec model = TestModels.small();
        ConversationContext conversation = declared(model, "question");
        RuntimeException fault = new RuntimeException("boom");
        MidStreamFaultClient client = new MidStreamFaultClient(fault);
        client.setModel(model);

        RuntimeException raised = assertThrows(RuntimeException.class,
                () -> client.streamResponse(new LLMRequest<>(conversation), chunk -> {}));

        assertSame(fault, raised,
                "a mid-stream failure the classifier does not recognize propagates as the provider threw it");
    }

    @Test
    void aClientWithoutAStreamingPath_refusesInTheFrameworksLanguage() {
        ModelSpec model = TestModels.small();
        ConversationContext conversation = declared(model, "question");
        TemplateStubClient client = new TemplateStubClient(LLMStopReason.END_TURN);
        client.setModel(model);

        UncorrectableRuntimeLLMException raised = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> client.streamResponse(new LLMRequest<>(conversation), chunk -> {}),
                "a client that never implemented doStreamResponse refuses as an LLM-readable failure");
        assertTrue(raised.getLLMMessage().contains(TemplateStubClient.class.getSimpleName()),
                "the refusal names the client so the caller knows which route lacks streaming");
    }

    @Test
    void reasoningEffort_mapsEveryDepthOntoTheThreeWords() {
        assertEquals("low", AbstractLLMClient.reasoningEffortFor(Depth.IMMEDIATE),
                "a reasoning-effort model has no 'none', so IMMEDIATE maps to the floor");
        assertEquals("low", AbstractLLMClient.reasoningEffortFor(Depth.QUICK));
        assertEquals("medium", AbstractLLMClient.reasoningEffortFor(Depth.STANDARD));
        assertEquals("high", AbstractLLMClient.reasoningEffortFor(Depth.THOROUGH));
        assertEquals("high", AbstractLLMClient.reasoningEffortFor(Depth.ULTRA_THOROUGH));
    }

    @Test
    void blockEncodersAreBuiltOncePerClientAndReused() {
        CountingTextEncoder.built.set(0);
        CountingEncoderClient client = new CountingEncoderClient();
        client.setModel(TestModels.small());

        assertEquals("first", client.encodeBlock(new TextBlock("first")),
                "the registered encoder renders the block through the client's text wrapper");
        assertEquals("second", client.encodeBlock(new TextBlock("second")));
        assertEquals(1, CountingTextEncoder.built.get(),
                "the encoder is instantiated once per client and cached, not once per block");
    }

    @Test
    void aBlockTypeWhoseEncoderMappingWasDropped_failsLoudNamingTheType() {
        MissingEncoderClient client = new MissingEncoderClient();
        client.setModel(TestModels.small());

        UncorrectableRuntimeLLMException raised = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> client.encodeBlock(new TextBlock("text")),
                "a block type with no registered encoder is a programming error surfaced loudly");
        assertTrue(raised.getLLMMessage().contains(TextBlock.class.getName()),
                "the failure names the block type whose default was dropped");
    }

    @Test
    void additiveUsage_sumsRegularAndCacheCountersIntoTheGrandTotal() {
        ConversationContext conversation = declared(TestModels.small(), "question");
        LLMResponse<String> response = new LLMResponse<>(new LLMRequest<>(conversation));

        response.setAdditiveUsage(100, 20, 30, 5);

        assertEquals(150, response.getActualInputTokens(),
                "additive providers report cache counters outside their input field, so the total is the sum");
        assertEquals(20, response.getCacheCreationInputTokens());
        assertEquals(30, response.getCacheReadInputTokens());
        assertEquals(5, response.getActualOutputTokens());
    }

    @Test
    void plainUsage_takesTheGrandTotalAsReported() {
        ConversationContext conversation = declared(TestModels.small(), "question");
        LLMResponse<String> response = new LLMResponse<>(new LLMRequest<>(conversation));

        response.setUsage(100, null, 40, 5);

        assertEquals(100, response.getActualInputTokens(),
                "a provider whose input field already includes the cached subset is taken at its word");
        assertEquals(40, response.getCacheReadInputTokens());
    }

    /** A {@link TextBlockEncoder} that counts its constructions, to pin the per-client cache. */
    public static class CountingTextEncoder extends TextBlockEncoder<String> {
        static final AtomicInteger built = new AtomicInteger();

        public CountingTextEncoder() {
            built.incrementAndGet();
        }
    }

    /** A client whose provider always reports the given stop reason and no content. */
    private static class TemplateStubClient extends AbstractLLMClient<String> {

        final AtomicInteger providerCalls = new AtomicInteger();
        private final LLMStopReason stopReason;

        private TemplateStubClient(LLMStopReason stopReason) {
            this.stopReason = stopReason;
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
            providerCalls.incrementAndGet();
            LLMResponse<T> response = new LLMResponse<>(request);
            response.setStopReason(stopReason);
            response.setUsage(10, null, null, 5);
            return response;
        }

        @Override
        protected boolean is429Error(Exception e) {
            return false;
        }

        @Override
        protected boolean isServerError(Exception e) {
            return false;
        }

        @Override
        protected boolean isOverloadError(Exception e) {
            return false;
        }

        @Override
        protected RateLimitInfo extractRateLimitInfo(Exception e) {
            return null;
        }
    }

    /** The same stub with a streaming path that reports the given stop reason and no chunks. */
    private static class StreamingStubClient extends TemplateStubClient {

        private final LLMStopReason stopReason;

        private StreamingStubClient(LLMStopReason stopReason) {
            super(stopReason);
            this.stopReason = stopReason;
        }

        @Override
        protected <T> void doStreamResponse(LLMRequest<T> request, LLMResponse<T> response,
                PreparedConversation prepared, java.util.function.Consumer<StreamChunk> chunkHandler) {
            response.setStopReason(stopReason);
            response.setUsage(10, null, null, 5);
        }
    }

    /** A streaming client whose provider refuses with a 429 before delivering anything. */
    private static class PreStream429Client extends TemplateStubClient {

        private PreStream429Client() {
            super(LLMStopReason.END_TURN);
        }

        @Override
        protected <T> void doStreamResponse(LLMRequest<T> request, LLMResponse<T> response,
                PreparedConversation prepared, java.util.function.Consumer<StreamChunk> chunkHandler) {
            throw new RuntimeException("simulated 429");
        }

        @Override
        protected boolean is429Error(Exception e) {
            return e.getMessage() != null && e.getMessage().contains("simulated 429");
        }
    }

    /** A streaming client whose provider delivers one chunk and then reports overload. */
    private static class MidStreamOverloadClient extends TemplateStubClient {

        private MidStreamOverloadClient() {
            super(LLMStopReason.END_TURN);
        }

        @Override
        protected <T> void doStreamResponse(LLMRequest<T> request, LLMResponse<T> response,
                PreparedConversation prepared, java.util.function.Consumer<StreamChunk> chunkHandler) {
            chunkHandler.accept(StreamChunk.of("partial"));
            throw new RuntimeException("simulated 529 overloaded");
        }

        @Override
        protected boolean isOverloadError(Exception e) {
            return e.getMessage() != null && e.getMessage().contains("simulated 529");
        }
    }

    /** A streaming client whose provider delivers one chunk and then fails unclassifiably. */
    private static class MidStreamFaultClient extends TemplateStubClient {

        private final RuntimeException fault;

        private MidStreamFaultClient(RuntimeException fault) {
            super(LLMStopReason.END_TURN);
            this.fault = fault;
        }

        @Override
        protected <T> void doStreamResponse(LLMRequest<T> request, LLMResponse<T> response,
                PreparedConversation prepared, java.util.function.Consumer<StreamChunk> chunkHandler) {
            chunkHandler.accept(StreamChunk.of("partial"));
            throw fault;
        }
    }

    /** A client whose text encoder counts constructions. */
    private static class CountingEncoderClient extends TemplateStubClient {

        private CountingEncoderClient() {
            super(LLMStopReason.END_TURN);
        }

        @Override
        protected Map<Class<? extends ContentBlock>, Class<? extends BlockEncoder<String>>> buildEncoders() {
            Map<Class<? extends ContentBlock>, Class<? extends BlockEncoder<String>>> m = super.buildEncoders();
            m.put(TextBlock.class, base(CountingTextEncoder.class));
            return m;
        }
    }

    /** A client whose override dropped the text mapping - the new-permitted-type-without-a-default mistake. */
    private static class MissingEncoderClient extends TemplateStubClient {

        private MissingEncoderClient() {
            super(LLMStopReason.END_TURN);
        }

        @Override
        protected Map<Class<? extends ContentBlock>, Class<? extends BlockEncoder<String>>> buildEncoders() {
            Map<Class<? extends ContentBlock>, Class<? extends BlockEncoder<String>>> m = super.buildEncoders();
            m.remove(TextBlock.class);
            return m;
        }
    }
}
