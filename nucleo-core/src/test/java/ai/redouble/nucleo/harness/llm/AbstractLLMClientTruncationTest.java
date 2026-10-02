/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the decisions {@code AbstractLLMClient}'s single-response template makes
 * around a provider's answer: the refusal gate ({@code throwIfRefused}, when a provider
 * reports a filtered stop with an empty body - the call fails as {@code ProviderRefusalException},
 * uncorrectable on that model, carrying the model, the provider's category and its explanation
 * for a caller resubmitting elsewhere, never reaching the parser as an empty response), the
 * truncation branch ({@code throwIfTruncated}, when a provider reports
 * {@code stop_reason=max_tokens}) and the per-response stamps the template owns (the end
 * time that gives every successful row a real latency).
 *
 * <p>On truncation: the framework's deterministic retry only helps while a larger budget
 * exists. Since {@link ConversationContext#resolveOutputBudget()} caps at the model's
 * ceiling, a call issued at that ceiling has nowhere to grow, and retrying would spend a
 * second identical upstream call to reach the same truncation. These tests pin both sides
 * of that decision.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-23)
 */
public class AbstractLLMClientTruncationTest {

    /** The output every conversation in these tests declares. */
    private static final int TEST_DEFAULT = 16_000;

    @Test
    void truncationBelowCeiling_signalsRetryAtTheCeiling() {
        ModelSpec model = TestModels.small();
        assertTrue(model.getMaxOutputTokens() > TEST_DEFAULT,
                "fixture assumption: the small tier's ceiling leaves room above the default budget");

        OutputTruncationRetryException raised = assertThrows(OutputTruncationRetryException.class,
                () -> call(model, null));

        assertEquals(TEST_DEFAULT, raised.getPreviousBudget(),
                "the budget that truncated is the one the call was issued with");
        assertEquals(model.getMaxOutputTokens(), raised.getNewBudget(),
                "the retry grows to the model ceiling, which is the largest budget that exists");
    }

    @Test
    void truncationAtCeiling_failsInsteadOfRetrying() {
        ModelSpec model = TestModels.small();

        UncorrectableRuntimeLLMException raised = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> call(model, model.getMaxOutputTokens()));

        assertTrue(raised.getLLMMessage().contains(String.valueOf(model.getMaxOutputTokens())),
                "the failure names the ceiling the answer did not fit in");
    }

    @Test
    void modelWhoseCeilingIsBelowTheDefault_failsAtItsOwnCeiling() {
        // The micro tier's ceiling sits below the project default, so its very first call is
        // already issued at the ceiling and no retry can ever help it.
        ModelSpec model = TestModels.micro();
        assertTrue(model.getMaxOutputTokens() < TEST_DEFAULT,
                "fixture assumption: the micro tier's ceiling sits below the default budget");

        assertThrows(UncorrectableRuntimeLLMException.class, () -> call(model, null));
    }

    /** A client-internal conversation whose seat declares no reasoning and a {@link #TEST_DEFAULT} answer. */
    private static ConversationContext declared(ModelSpec model) {
        ConversationContext conversation = TestModels.conversation(model);
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(TEST_DEFAULT));
        return conversation;
    }

    /**
     * Drives one truncated response through the client template. A null
     * {@code requestedOutputTokens} leaves the budget at the conversation's declaration.
     */
    private void call(ModelSpec model, Integer requestedOutputTokens) throws TokenEstimateExceedsLimitException {
        ConversationContext conversation = declared(model);
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText("question");
        if (requestedOutputTokens != null) {
            message.setRequestedOutputTokens(requestedOutputTokens);
        }
        conversation.getMessages().add(message);
        StubProviderClient client = new StubProviderClient(LLMStopReason.MAX_TOKENS);
        client.setModel(model);
        client.singleResponse(new LLMRequest<>(conversation));
    }

    @Test
    void successfulSingleResponse_carriesEndTimeFromTheTemplate() throws TokenEstimateExceedsLimitException {
        ModelSpec model = TestModels.small();
        ConversationContext conversation = declared(model);
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText("question");
        conversation.getMessages().add(message);
        StubProviderClient client = new StubProviderClient(LLMStopReason.END_TURN);
        client.setModel(model);

        LLMResponse<String> response = client.singleResponse(new LLMRequest<>(conversation));

        assertTrue(response.getEndTime() != null,
                "the template stamps the end time for every client - a per-client convention left"
                        + " every single-path success with a null end time and a zero latency");
        assertTrue(!response.getEndTime().isBefore(response.getStartTime()),
                "and the stamp follows the start, so latency is non-negative");
    }

    @Test
    void untypedStopReasonWithOutputAtTheCeiling_backstopsAsTruncation() {
        // Anthropic-over-Bedrock surfaces no typed stop reason; without the backstop a
        // clipped answer would silently pass through as a complete one.
        ModelSpec model = TestModels.small();
        OutputTruncationRetryException raised = assertThrows(OutputTruncationRetryException.class,
                () -> callReporting(model, LLMStopReason.UNKNOWN, TEST_DEFAULT));
        assertEquals(TEST_DEFAULT, raised.getPreviousBudget());
    }

    @Test
    void untypedStopReasonOneTokenShortOfTheCeiling_isACompleteAnswer() throws TokenEstimateExceedsLimitException {
        ModelSpec model = TestModels.small();
        LLMResponse<String> response = callReporting(model, LLMStopReason.UNKNOWN, TEST_DEFAULT - 1);
        assertEquals(LLMStopReason.UNKNOWN, response.getStopReason(),
                "below the ceiling, an untyped stop reason is not treated as truncation");
    }

    /** Drives a response whose provider reports the given stop reason and output token count. */
    private LLMResponse<String> callReporting(ModelSpec model, LLMStopReason stopReason, int outputTokens)
            throws TokenEstimateExceedsLimitException {
        ConversationContext conversation = declared(model);
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText("question");
        conversation.getMessages().add(message);
        StubProviderClient client = new StubProviderClient(stopReason, outputTokens);
        client.setModel(model);
        return client.singleResponse(new LLMRequest<>(conversation));
    }

    @Test
    void aRefusedAnswerFailsAsAProviderRefusalCarryingModelCategoryAndExplanation() {
        ModelSpec model = TestModels.small();
        ConversationContext conversation = declared(model);
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText("question");
        conversation.getMessages().add(message);
        StubProviderClient client = new StubProviderClient(LLMStopReason.CONTENT_FILTERED, 2);
        client.refusalCategory = "reasoning_extraction";
        client.refusal = "This request was blocked";
        client.setModel(model);
        ProviderRefusalException raised = assertThrows(ProviderRefusalException.class,
                () -> client.singleResponse(new LLMRequest<>(conversation)));
        assertEquals(model.getId(), raised.getModelId(), "the refusal names the model that refused, for a caller choosing another");
        assertEquals("reasoning_extraction", raised.getCategory(), "the provider's category word, the field a caller branches on");
        assertEquals("This request was blocked", raised.getExplanation(), "the provider's explanation, for display");
        assertFalse(raised.isCorrectable(), "uncorrectable on this model: a safety classifier is not re-rolled");
        assertTrue(raised.getMessage().contains(model.getId()) && raised.getMessage().contains("(reasoning_extraction)")
                        && raised.getMessage().contains("This request was blocked"),
                "the message names the model and carries the provider's category and explanation: " + raised.getMessage());
        assertTrue(conversation.getMessages().size() == 1, "the refused, empty answer never joins the conversation");
    }

    @Test
    void aRefusalWithoutAnAccountStillFailsAsARefusal() {
        ModelSpec model = TestModels.small();
        ProviderRefusalException raised = assertThrows(ProviderRefusalException.class,
                () -> callReporting(model, LLMStopReason.CONTENT_FILTERED, 0));
        assertNull(raised.getCategory(), "no category when the provider named none");
        assertNull(raised.getExplanation(), "no explanation when the provider gave none");
        assertTrue(raised.getMessage().contains("refused") && raised.getMessage().contains("gave no account"), raised.getMessage());
    }

    /** A client whose provider always reports the given stop reason and no content, and a refusal category and explanation when set. */
    private static final class StubProviderClient extends AbstractLLMClient<String> {

        private final LLMStopReason stopReason;
        private final Integer outputTokens;
        private String refusalCategory;
        private String refusal;

        private StubProviderClient(LLMStopReason stopReason) {
            this(stopReason, null);
        }

        private StubProviderClient(LLMStopReason stopReason, Integer outputTokens) {
            this.stopReason = stopReason;
            this.outputTokens = outputTokens;
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
            LLMResponse<T> response = new LLMResponse<>(request);
            response.setStopReason(stopReason);
            response.setRefusalCategory(refusalCategory);
            response.setRefusal(refusal);
            if (outputTokens != null) {
                response.setUsage(0, null, null, outputTokens);
            }
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
}
