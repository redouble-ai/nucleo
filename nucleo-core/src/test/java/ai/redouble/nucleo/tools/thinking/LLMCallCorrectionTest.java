/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the self-correction loop of {@link LLMCall} - the "self-correcting agent" promise:
 * a response that fails to parse (or parses but fails required-field validation) is fed
 * back to the model as a user-role correction message carrying the error explanation and
 * the ORIGINAL response handler, under a budget of {@link LLMCall#MAX_CORRECTIONS} per
 * job. On exhaustion, a parse failure surfaces as {@link JsonParseException} while a
 * validation failure returns the response as-is. A truncation signal grows the outgoing
 * message's budget and rethrows for the dispatcher's one-shot retry.
 *
 * <p>The client is a stub behind {@code LLMCall}'s package-private seam; the loop's own
 * decisions - what gets appended, what gets thrown, when the budget runs out - execute
 * for real.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
public class LLMCallCorrectionTest {

    /** Typed answer with a required field, so an empty object fails validation. */
    public static class StrictAnswer {
        @LLMRequired
        private String verdict;

        public String getVerdict() { return verdict; }
        public void setVerdict(String verdict) { this.verdict = verdict; }
    }

    private static Identifiable root() {
        return Job.workflow("llmcall-correction-test", "llmcall-correction-test");
    }

    /** Client whose provider answers with the given raw content, or throws. */
    static final class CannedClient extends AbstractLLMClient<String> {
        private final String rawContent;
        private final LLMStopReason stopReason;
        private final RuntimeException toThrow;

        CannedClient(String rawContent) {
            this(rawContent, LLMStopReason.END_TURN);
        }

        CannedClient(String rawContent, LLMStopReason stopReason) {
            this.rawContent = rawContent;
            this.stopReason = stopReason;
            this.toThrow = null;
        }

        CannedClient(RuntimeException toThrow) {
            this.rawContent = null;
            this.stopReason = null;
            this.toThrow = toThrow;
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
            if (toThrow != null) {
                throw toThrow;
            }
            LLMResponse<T> response = new LLMResponse<>(request);
            response.setStopReason(stopReason);
            response.getResponseMessage().overwriteRawContent(rawContent);
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

    /** LLMCall wired to the canned client through the package-private seam. */
    static final class TestableCall extends LLMCall<StrictAnswer> {
        private final CannedClient client;

        TestableCall(ConversationContext conversation, CannedClient client) {
            super(root(), conversation);
            this.client = client;
            client.setModel(TestModels.small());
        }

        @Override
        LLMClient client(JobResources resources) {
            return client;
        }
    }

    /** The output the seat declares; below the small tier's ceiling so an escalation has room to grow. */
    private static final int DECLARED_OUTPUT = 16_000;

    private static ConversationContext conversationExpecting(PojoResponseHandler<StrictAnswer> handler) {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(DECLARED_OUTPUT));
        OutgoingMessage<StrictAnswer> message = new OutgoingMessage<>(handler);
        message.setRole("user");
        message.addText("answer strictly");
        conversation.getMessages().add(message);
        return conversation;
    }

    @Test
    void unparseableResponseFeedsACorrectionBack_boundedThenSurfacesTheParseFailure() {
        ConversationContext conversation = conversationExpecting(new PojoResponseHandler<>(StrictAnswer.class));
        TestableCall call = new TestableCall(conversation, new CannedClient("this is not json"));
        int before = conversation.getMessages().size();

        for (int attempt = 1; attempt <= LLMCall.MAX_CORRECTIONS; attempt++) {
            assertThrows(ResponseCorrectionRetryException.class, () -> call.execute(null, null),
                    "within budget, the failure becomes a transparent retry signal");
        }
        assertThrows(JsonParseException.class, () -> call.execute(null, null),
                "past the budget, the parse failure surfaces instead of looping forever");

        long corrections = conversation.getMessages().stream()
                .skip(before)
                .filter(m -> "user".equals(m.getRole()))
                .count();
        assertEquals(LLMCall.MAX_CORRECTIONS, corrections,
                "each retry carried the error back to the model as a user-role correction turn");
        Message lastCorrection = conversation.getMessages().get(conversation.getMessages().size() - 1);
        assertNotNull(lastCorrection.getRawContent());
        assertFalse(lastCorrection.getRawContent().isEmpty(),
                "the correction explains the failure rather than just asking again");
    }

    @Test
    void validationFailureCorrects_thenReturnsAsIsOnExhaustion() throws Exception {
        ConversationContext conversation = conversationExpecting(new PojoResponseHandler<>(StrictAnswer.class));
        TestableCall call = new TestableCall(conversation, new CannedClient("{}"));

        for (int attempt = 1; attempt <= LLMCall.MAX_CORRECTIONS; attempt++) {
            assertThrows(ResponseCorrectionRetryException.class, () -> call.execute(null, null),
                    "a parsed-but-invalid answer is corrected like a parse failure");
        }
        StrictAnswer asIs = call.execute(null, null);
        assertNotNull(asIs, "on exhaustion, validation degrades to a WARN and the response is returned as-is");
        assertNull(asIs.getVerdict(), "the required field really was missing - hard enforcement is the caller's boundary");
    }

    @Test
    void truncationEscalatesTheRetainedMessageAndRethrows() {
        // The escalation is the client's: a truncated response bumps the SENT message to the model
        // ceiling before the retry signal. LLMCall keeps the thinker's conversation across attempts,
        // so the re-run reserves and requests the escalated budget with nothing of its own to do.
        ConversationContext conversation = conversationExpecting(new PojoResponseHandler<>(StrictAnswer.class));
        OutgoingMessage<?> outgoing = (OutgoingMessage<?>)conversation.getMessages().get(conversation.getMessages().size() - 1);
        ModelSpec model = TestModels.small();
        assertTrue(model.getMaxOutputTokens() > DECLARED_OUTPUT, "fixture assumption: room above the declaration");
        TestableCall call = new TestableCall(conversation, new CannedClient("{\"verdict\": \"cut of", LLMStopReason.MAX_TOKENS));

        OutputTruncationRetryException raised = assertThrows(OutputTruncationRetryException.class, () -> call.execute(null, null));
        assertEquals(DECLARED_OUTPUT, raised.getPreviousBudget(), "the budget that truncated is the declaration");
        assertEquals(model.getMaxOutputTokens(), raised.getNewBudget(), "the retry grows to the model ceiling");
        assertEquals(model.getMaxOutputTokens(), outgoing.getRequestedOutputTokens(),
                "the retained message carries the escalation, so the re-run reserves and requests it");
    }
}
