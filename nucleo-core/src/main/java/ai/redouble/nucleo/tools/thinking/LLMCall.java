/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;

import java.time.*;

/**
 * The one exchange a thinker's turn is, dispatched as a job of its own over the thinker's
 * {@link ConversationContext}: the last outgoing message's {@link ResponseHandler} defines
 * the answer type. Thinkers stay resource-free and spawn one of these per round-trip, so the
 * reservation, the output budget and the truncation ceiling are scoped to the call alone,
 * and the reservation is the input plus {@link ConversationContext#resolveOutputBudget()},
 * the same number the client sends as {@code max_tokens}.
 *
 * <p>The call is a model-dependent tool like every other exchange with a model in the
 * runtime, built on {@link AbstractModelDependentTool}: the conversation is wired at
 * requirements time, the exchange runs through {@code converse}, and a malformed answer is
 * corrected under the shared {@link ResponseCorrection} budget. What is the call's own is
 * its seat: the conversation's grade, its depth, and a pin when the thinker that spawned it
 * is pinned, so a benchmark that pins a thinker pins every call it makes.
 *
 * <p>On {@link OutputTruncationRetryException} (raised by a client when the response hits
 * {@code stop_reason=max_tokens}) the client has already bumped the outgoing message's
 * {@code requestedOutputTokens} to the model ceiling; because the thinker's conversation is
 * kept across attempts, the dispatcher's one-time re-run reserves and sends the escalated
 * budget.
 *
 * @param <T> the answer type the last outgoing message's handler declares
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-22)
 */
@DisplayName(value = "Thinking", action = "Thinking")
public class LLMCall<T> extends AbstractModelDependentTool<Void, T> {
    /** Correction budget per job: at most this many parse/validation feedback retries. */
    public static final int MAX_CORRECTIONS = ResponseCorrection.MAX_CORRECTIONS;
    private final ConversationContext conversation;

    /**
     * The model is the RESOLUTION's own, never an argument: this job declares the
     * conversation's grade and depth through its seat, wires the resolved binding to the
     * conversation, and the harness resolves it before execution - so the rate-limiter
     * reservation, the output budget, the wire ceiling and the truncation ceiling all read
     * one resolved cell and cannot silently diverge.
     */
    public LLMCall(Identifiable parent, ConversationContext conversation) {
        super(parent, buildJobIdPrefix(parent));
        setTimeout(Duration.ofMinutes(6));
        if (conversation == null) {
            throw new IllegalArgumentException("Conversation cannot be null");
        }
        this.conversation = conversation;
    }

    /**
     * Builds the job ID prefix, including the parent's display name if available.
     * E.g., "llm-call-QueryAnalyzerAgent" instead of just "llm-call".
     */
    private static String buildJobIdPrefix(Identifiable parent) {
        if (parent instanceof JobContext<?> ctx) {
            String displayName = ctx.getSnapshot().getDisplayName();
            if (displayName != null && !displayName.isEmpty()) {
                // Sanitize: remove spaces and special chars, keep alphanumeric
                String sanitized = displayName.replaceAll("[^a-zA-Z0-9]", "");
                if (!sanitized.isEmpty()) {
                    return "llm-call-" + sanitized;
                }
            }
        }
        return "llm-call";
    }

    @Override
    public JobType getJobType() {
        return JobType.LLM_CALL;
    }

    /** The seat's grade is the conversation's, read and written there, never held by the call. */
    @Override
    public Grade getGrade() {
        return conversation.getGrade();
    }

    @Override
    public void setGrade(Grade grade) {
        conversation.setGrade(grade);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        // The wired form: the harness resolves the binding through the picker (or takes the
        // pin), then the binding prices itself from the conversation - input under the
        // resolved spec's tokenizer, output from resolveOutputBudget, thinking from the spec
        // and depth. That keeps the reservation equal to the max_tokens the SDK sends
        // upstream: Anthropic pre-debits max_tokens = answer + thinking from its TPM bucket,
        // so both sides of the boundary agree by construction.
        wireConversation(req, conversation.resolveDepth(), () -> conversation);
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        return req;
    }

    @Override
    public T execute(JobResources resources, JobContext<T> context) throws LLMReadableCheckedException {
        try {
            return converse(resources);
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }

    /**
     * The answer is already recorded on the job as the call's response by the client wrapper;
     * a tool's output metadata would repeat the whole turn beside it.
     */
    @Override
    public void postExecute(JobContext<T> context) {
    }
}
