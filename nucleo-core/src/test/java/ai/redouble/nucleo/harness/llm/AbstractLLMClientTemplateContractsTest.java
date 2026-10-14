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
import ai.redouble.nucleo.harness.tokens.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the promises {@code AbstractLLMClient}'s templates make regardless of provider:
 * the pre-flight estimate gate refuses an oversized request before the provider is called on both
 * paths with a message that says it was an estimate, each template estimates a call exactly once,
 * a client without a model refuses before estimating, an estimate above a tenth of the
 * model's per-minute budget is logged at WARN, the single path alone consults the provider's exact
 * count past three quarters of the window, the usage ledger stamps the response message's output
 * count and a missing ledger stamps nothing, the
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

        TokenEstimateExceedsLimitException refusal = assertThrows(TokenEstimateExceedsLimitException.class,
                () -> client.singleResponse(new LLMRequest<>(conversation)),
                "the pre-flight gate refuses an estimate above the context window");
        assertEquals(0, client.providerCalls.get(),
                "the refusal happens before any provider call is spent");
        assertTrue(refusal.getMessage().toLowerCase().contains("estimate"), "the refusal says it was an estimate: " + refusal.getMessage());
    }

    @Test
    void eachTemplateEstimatesTheCallOnce() throws Exception {
        int[] estimates = {0};
        StandardModelSpec counting = new StandardModelSpec() {
            @Override
            public TokenLedger estimate(ConversationContext conversation) {
                estimates[0]++;
                return super.estimate(conversation);
            }
        };
        counting.setId("counting-estimates");
        counting.setIdentity("counting-estimates");
        counting.setProviderKey("test");
        counting.setWireModelId("counting-estimates");
        counting.setGrade(Grade.SMALL);
        counting.setMaxContextTokens(100_000);
        counting.setMaxOutputTokens(20_000);
        counting.setTpm(1_000_000);
        counting.setRpm(100);
        TemplateStubClient client = new TemplateStubClient(LLMStopReason.END_TURN);
        client.setModel(counting);
        client.singleResponse(new LLMRequest<>(declared(counting, "question")));
        assertEquals(1, estimates[0], "one estimate serves the whole single call");
        StreamingStubClient streaming = new StreamingStubClient(LLMStopReason.END_TURN);
        streaming.setModel(counting);
        streaming.streamResponse(new LLMRequest<>(declared(counting, "question")), chunk -> {});
        assertEquals(2, estimates[0], "and one serves the whole stream");
    }

    @Test
    void theStreamingTemplateRunsTheSamePreFlight() {
        ModelSpec model = TestModels.micro();
        String oversized = "a ".repeat(model.getMaxContextTokens() * 4);
        ConversationContext conversation = declared(model, oversized);
        StreamingStubClient client = new StreamingStubClient(LLMStopReason.END_TURN);
        client.setModel(model);
        assertThrows(TokenEstimateExceedsLimitException.class,
                () -> client.streamResponse(new LLMRequest<>(conversation), chunk -> {}),
                "a stream that cannot fit is refused before the provider is called, like a single call");
    }

    @Test
    void aClientWithoutAModelRefusesBeforeEstimating() {
        TemplateStubClient client = new TemplateStubClient(LLMStopReason.END_TURN);
        ConversationContext conversation = declared(TestModels.small(), "question");
        assertThrows(IllegalStateException.class, () -> client.singleResponse(new LLMRequest<>(conversation)),
                "a client is built for a spec by its provider; without one there is nothing to estimate against");
        assertThrows(IllegalStateException.class, () -> client.streamResponse(new LLMRequest<>(conversation), chunk -> {}),
                "the streaming template refuses the same way");
        assertEquals(0, client.providerCalls.get(), "and no provider call was spent");
    }

    /** A spec whose per-minute budget an ordinary call takes a tenth of, while its window still fits the call. */
    private static StandardModelSpec tightBudget() {
        StandardModelSpec s = new StandardModelSpec();
        s.setId("tight-budget");
        s.setIdentity("tight-budget");
        s.setProviderKey("test");
        s.setWireModelId("tight-budget");
        s.setGrade(Grade.SMALL);
        s.setMaxContextTokens(100_000);
        s.setMaxOutputTokens(2_000);
        s.setTpm(1_000);
        s.setRpm(100);
        return s;
    }

    @Test
    void anEstimateAboveATenthOfTheModelsPerMinuteBudgetIsLoggedAtWarn() throws Exception {
        StandardModelSpec model = tightBudget();
        ConversationContext conversation = declared(model, "question");
        TokenLedger estimate = model.estimate(conversation);
        int whole = estimate.inputTokens() + estimate.outputTokens();
        assertTrue(whole > model.getTpm() / 10 && whole <= model.getMaxContextTokens(),
                "fixture: above a tenth of the per-minute budget yet inside the window: " + estimate);
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AbstractLLMClient.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            TemplateStubClient client = new TemplateStubClient(LLMStopReason.END_TURN);
            client.setModel(model);
            client.singleResponse(new LLMRequest<>(conversation));
        }
        finally {
            logger.detachAppender(appender);
        }
        List<ch.qos.logback.classic.spi.ILoggingEvent> large = appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("Large token estimate for " + model.getId()))
                .toList();
        assertEquals(1, large.size(), "one warning for the one call, naming the model: " + appender.list);
        assertEquals(ch.qos.logback.classic.Level.WARN, large.get(0).getLevel(), "at WARN: one such call starves the fleet for that minute");
    }

    /** A conversation declaring a small answer, so a near-full input still passes the pre-flight. */
    private static ConversationContext nearlyFull(ModelSpec model) {
        ConversationContext conversation = TestModels.conversation(model);
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(100));
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText("a ".repeat((int)(model.getMaxContextTokens() * 0.8)));
        conversation.getMessages().add(message);
        return conversation;
    }

    @Test
    void anEstimatePastThreeQuartersOfTheWindowConsultsTheProvidersExactCount_onTheSinglePath() throws Exception {
        ModelSpec model = TestModels.micro();
        ExactCountingClient refusing = new ExactCountingClient(model.getMaxContextTokens());
        refusing.setModel(model);
        TokenLedger estimate = model.estimate(nearlyFull(model));
        assertTrue(estimate.inputTokens() > model.getMaxContextTokens() * 0.75 && estimate.inputTokens() + estimate.outputTokens() <= model.getMaxContextTokens(),
                "fixture: past three quarters of the window yet under it, so the estimate alone passes: " + estimate);
        assertThrows(TokenEstimateExceedsLimitException.class, () -> refusing.singleResponse(new LLMRequest<>(nearlyFull(model))),
                "the exact count plus the output reserve does not fit, so the call is refused");
        assertEquals(1, refusing.exactCounts.get(), "the exact count was consulted once");
        assertEquals(0, refusing.providerCalls.get(), "and no provider call was spent");
        ExactCountingClient unsupported = new ExactCountingClient(-1);
        unsupported.setModel(model);
        unsupported.singleResponse(new LLMRequest<>(nearlyFull(model)));
        assertEquals(1, unsupported.exactCounts.get(), "a provider with no server-side count is asked once");
        assertEquals(1, unsupported.providerCalls.get(), "and answers -1, so the estimate stands and the call goes out");
        ExactCountingClient streaming = new ExactCountingClient(model.getMaxContextTokens());
        streaming.setModel(model);
        streaming.streamResponse(new LLMRequest<>(nearlyFull(model)), chunk -> {});
        assertEquals(0, streaming.exactCounts.get(), "the streaming template never consults the exact count");
    }

    @Test
    void aResponseWithoutAUsageRecordStampsNothingOnItsMessage() throws Exception {
        NoUsageClient client = new NoUsageClient();
        client.setModel(TestModels.small());
        LLMResponse<String> response = client.singleResponse(new LLMRequest<>(declared(TestModels.small(), "question")));
        assertNull(response.getUsage(), "the provider reported no usage, so there is no ledger");
        assertNull(response.getResponseMessage().getActualOutputTokens(), "and nothing is stamped on the message, never a zero");
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
    void theUsageLedgerStampsTheResponseMessagesOutputCount_onBothTemplates() throws Exception {
        TemplateStubClient single = new TemplateStubClient(LLMStopReason.END_TURN);
        single.setModel(TestModels.small());
        LLMResponse<String> answered = single.singleResponse(new LLMRequest<>(declared(TestModels.small(), "question")));
        assertEquals(5, answered.getUsage().outputTokens(), "the provider's ledger rides the response");
        assertEquals(5, answered.getResponseMessage().getActualOutputTokens(),
                "the template stamps the assistant message with what it took to generate, once");
        StreamingStubClient streaming = new StreamingStubClient(LLMStopReason.END_TURN);
        streaming.setModel(TestModels.small());
        LLMResponse<String> streamed = streaming.streamResponse(new LLMRequest<>(declared(TestModels.small(), "question")), chunk -> {});
        assertEquals(5, streamed.getResponseMessage().getActualOutputTokens(),
                "a streamed response is stamped at completion, where its usage arrives");
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
            response.setUsage(new DefaultTokenLedger(getModel(), 10, null, null, 5));
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
            response.setUsage(new DefaultTokenLedger(getModel(), 10, null, null, 5));
        }
    }

    /** A client whose provider counts server-side and answers the given exact count, or -1 for no such count. */
    private static class ExactCountingClient extends StreamingStubClient {
        final AtomicInteger exactCounts = new AtomicInteger();
        private final int exact;

        private ExactCountingClient(int exact) {
            super(LLMStopReason.END_TURN);
            this.exact = exact;
        }

        @Override
        protected int countTokensExact(LLMRequest<?> request) {
            exactCounts.incrementAndGet();
            return exact;
        }
    }

    /** A client whose provider returns no usage record. */
    private static class NoUsageClient extends TemplateStubClient {
        private NoUsageClient() {
            super(LLMStopReason.END_TURN);
        }

        @Override
        protected <T> LLMResponse<T> doSingleResponse(LLMRequest<T> request, PreparedConversation prepared) {
            providerCalls.incrementAndGet();
            LLMResponse<T> response = new LLMResponse<>(request);
            response.setStopReason(LLMStopReason.END_TURN);
            return response;
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
