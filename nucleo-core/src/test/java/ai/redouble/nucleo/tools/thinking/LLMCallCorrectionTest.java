/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.artifacts.*;
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
 * job. On exhaustion the failure surfaces, a parse failure as {@link JsonParseException}
 * and a missing required field as {@link ResponseValidationException}. An artifact in the
 * reply is the conversation registry's own, by the reference the model chose, and one the
 * registry does not hold is corrected the way a missing required field is. A truncation signal grows the outgoing
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

    private static <T> ConversationContext conversationExpecting(PojoResponseHandler<T> handler) {
        ConversationContext conversation = TestModels.conversation(TestModels.small());
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(DECLARED_OUTPUT));
        OutgoingMessage<T> message = new OutgoingMessage<>(handler);
        message.setRole("user");
        message.addText("answer strictly");
        conversation.getMessages().add(message);
        return conversation;
    }

    /** An answer that hands back one citation the model chose. */
    public static class CitingAnswer {
        private CitationArtifact best;

        public CitationArtifact getBest() { return best; }
        public void setBest(CitationArtifact best) { this.best = best; }
    }

    /** LLMCall for the citing answer, wired to the canned client through the same seam. */
    static final class CitingCall extends LLMCall<CitingAnswer> {
        private final CannedClient client;

        CitingCall(ConversationContext conversation, CannedClient client) {
            super(root(), conversation);
            this.client = client;
            client.setModel(TestModels.small());
        }

        @Override
        LLMClient client(JobResources resources) {
            return client;
        }
    }

    @Test
    void anArtifactInTheAnswerIsTheRegistrysOwn_whateverTheModelWroteBesideItsReference() throws Exception {
        ConversationContext conversation = conversationExpecting(new PojoResponseHandler<>(CitingAnswer.class));
        CitationArtifact held = new CitationArtifact();
        held.setDoi("10.1038/s41586-024-07386-0");
        String ref = conversation.getArtifactRegistry().register(held);
        CitingCall call = new CitingCall(conversation, new CannedClient("{\"best\": {\"artifact_ref\": \"" + ref + "\", \"doi\": \"10.9999/invented\"}}"));

        CitingAnswer answer = call.execute(null, null);
        assertSame(held, answer.getBest(), "the answer carries the object the registry holds under the reference the model chose");
        assertEquals("10.1038/s41586-024-07386-0", answer.getBest().getDoi(), "a DOI the model wrote beside the reference never reaches the caller");
    }

    @Test
    void anArtifactTheRegistryDoesNotHoldIsCorrected_thenSurfaces() {
        ConversationContext conversation = conversationExpecting(new PojoResponseHandler<>(CitingAnswer.class));
        CitingCall call = new CitingCall(conversation, new CannedClient("{\"best\": {\"artifact_ref\": \"«artifact:link:cite~never1»\", \"doi\": \"10.9999/invented\"}}"));

        for (int attempt = 1; attempt <= LLMCall.MAX_CORRECTIONS; attempt++) {
            assertThrows(ResponseCorrectionRetryException.class, () -> call.execute(null, null),
                    "an artifact the registry cannot supply is fed back to the model like a missing required field");
        }
        ResponseValidationException surfaced = assertThrows(ResponseValidationException.class, () -> call.execute(null, null),
                "past the budget the call fails: a fabricated artifact is never returned");
        assertTrue(surfaced.explainToLLM().contains("best"), "the failure names the place: " + surfaced.explainToLLM());
        assertFalse(surfaced.explainToLLM().contains("invented"), "and never what the model wrote there: " + surfaced.explainToLLM());
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
    void validationFailureCorrects_thenSurfacesOnExhaustion() {
        ConversationContext conversation = conversationExpecting(new PojoResponseHandler<>(StrictAnswer.class));
        TestableCall call = new TestableCall(conversation, new CannedClient("{}"));

        for (int attempt = 1; attempt <= LLMCall.MAX_CORRECTIONS; attempt++) {
            assertThrows(ResponseCorrectionRetryException.class, () -> call.execute(null, null),
                    "a parsed-but-invalid answer is corrected like a parse failure");
        }
        ResponseValidationException surfaced = assertThrows(ResponseValidationException.class, () -> call.execute(null, null),
                "on exhaustion the missing required field fails the call, the way a parse failure does");
        assertTrue(surfaced.explainToLLM().contains("verdict"), "and the failure names the field that was required: " + surfaced.explainToLLM());
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
