/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import org.slf4j.*;
import java.io.*;
import java.util.*;

/**
 * The self-correction of one LLM exchange, shared by every job that sends a conversation
 * and expects a typed answer: a response that fails to parse, or parses but fails the
 * handler's required-field validation, is fed back to the model. The failed assistant
 * message is already in the conversation (clients append it); this appends a user-role
 * correction message carrying the error explanation and the ORIGINAL response handler,
 * then throws {@link ResponseCorrectionRetryException} for the dispatcher to re-run the
 * job transparently on the grown conversation. The budget is {@value #MAX_CORRECTIONS}
 * corrections per job instance, so it survives the dispatcher's re-runs. On exhaustion the
 * failure surfaces, a parse failure as {@link JsonParseException} and a missing required
 * field as {@link ResponseValidationException}: required is required, and no caller
 * receives an answer with a hole in it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
public final class ResponseCorrection {
    private static final Logger log = LoggerFactory.getLogger(ResponseCorrection.class);
    /** Correction budget per job: at most this many parse/validation feedback retries. */
    public static final int MAX_CORRECTIONS = 2;
    private int used;

    /** Corrections consumed so far by the job holding this. */
    public int getUsed() {
        return used;
    }

    /**
     * One exchange: the conversation's last outgoing message sent on the client, the answer
     * parsed or corrected. The outgoing message is taken before the call, since the client
     * appends the answer and the last message is no longer outgoing afterwards. What a
     * thinker's call and a one-call tool both do, written once.
     */
    public <T> T exchange(LLMClient client, ConversationContext conversation) throws LLMReadableCheckedException {
        OutgoingMessage<T> outgoingMessage = conversation.getLastOutgoingMessage();
        if (outgoingMessage == null) {
            throw new IllegalStateException("An exchange needs a conversation with an outgoing message to send");
        }
        LLMResponse<T> response = client.singleResponse(new LLMRequest<>(conversation));
        return parseOrCorrect(response, conversation, outgoingMessage);
    }

    /**
     * The parsed answer of a response, or the correction protocol: within budget the
     * failure becomes a correction turn and a retry signal; past it, the failure surfaces,
     * whether the answer did not parse or lacks a required field.
     *
     * @param response        what the client answered
     * @param conversation    the conversation the correction turn is appended to
     * @param outgoingMessage the message the response answered, taken before the call, since the
     *                        client appends the answer after it
     */
    public <T> T parseOrCorrect(LLMResponse<T> response, ConversationContext conversation, OutgoingMessage<T> outgoingMessage)
            throws LLMReadableCheckedException {
        T parsed;
        try {
            parsed = response.getResponseMessage().getResponse();
        }
        catch (IOException e) {
            log.warn("JSON parse error in the model's answer: {}", e.getMessage());
            JsonParseException parseFailure = new JsonParseException(e.getMessage(), e);
            if (used >= MAX_CORRECTIONS) {
                throw parseFailure;
            }
            throw correctionRetry(parseFailure, outgoingMessage, conversation);
        }
        // A model never authors an artifact: every one in the reply is taken from the
        // conversation's registry by its reference, before anything else reads the reply
        List<String> validationErrors = new ArrayList<>();
        parsed = outgoingMessage.getResponseHandler().heldArtifacts(parsed, conversation.getArtifactRegistry(), validationErrors);
        validationErrors.addAll(outgoingMessage.getResponseHandler().getValidationErrors(parsed));
        if (!validationErrors.isEmpty()) {
            ResponseValidationException validationFailure = new ResponseValidationException(validationErrors);
            // gh-13: required is required, so past the budget the failure surfaces
            if (used >= MAX_CORRECTIONS) {
                throw validationFailure;
            }
            throw correctionRetry(validationFailure, outgoingMessage, conversation);
        }
        return parsed;
    }

    /**
     * Appends a user-role correction message to the conversation and builds the retry
     * signal. The correction message reuses the original outgoing message's response
     * handler so the retried call still parses as {@code T}, and copies its output budget
     * so the reservation math stays consistent.
     */
    private <T> ResponseCorrectionRetryException correctionRetry(LLMReadableException failure, OutgoingMessage<T> original,
                                                                 ConversationContext conversation) {
        used++;
        OutgoingMessage<T> correction = new OutgoingMessage<>(original.getResponseHandler());
        correction.setRole("user");
        correction.addText(failure.explainToLLM());
        correction.setRequestedOutputTokens(original.getRequestedOutputTokens());
        conversation.getMessages().add(correction);
        return new ResponseCorrectionRetryException(conversation.getModel().getId(), used, (Throwable) failure);
    }
}
