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
 * corrections per job instance, so it survives the dispatcher's re-runs. On exhaustion a
 * parse failure surfaces as {@link JsonParseException} while a validation failure logs a
 * structured WARN and returns the response as-is: contracts that demand hard validation
 * enforce it at their own boundary.
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
     * failure becomes a correction turn and a retry signal; past it, a parse failure
     * surfaces and an invalid answer is returned as it is.
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
        List<String> validationErrors = outgoingMessage.getResponseHandler().getValidationErrors(parsed);
        if (!validationErrors.isEmpty()) {
            if (used >= MAX_CORRECTIONS) {
                log.warn("Validation exhausted after {} correction(s), returning the answer as-is. type={} errors={}",
                        used, parsed != null ? parsed.getClass().getName() : "null", validationErrors);
                return parsed;
            }
            throw correctionRetry(new ResponseValidationException(validationErrors), outgoingMessage, conversation);
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
