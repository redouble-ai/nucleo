/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;

import java.time.*;

/**
 * Short-lived job that runs a single LLM call against a supplied
 * {@link ConversationContext}. The last outgoing message's
 * {@link ResponseHandler} defines the response type.
 *
 * <p>Resource lifetime is scoped to this job only: thinkers stay
 * resource-free and spawn one {@code LLMCall} per round-trip. The job
 * reserves its input tokens plus
 * {@link ConversationContext#resolveOutputBudget()} from the model's rate
 * limiter, which is the same number the SDK client sends as
 * {@code max_tokens}. This keeps local bucket accounting and upstream
 * pre-debit in sync.
 *
 * <p>The seat is the conversation's: its grade, its depth, and a pin when the thinker that
 * spawned this call is pinned, so a benchmark that pins a thinker pins every call it makes.
 *
 * <p>On {@link OutputTruncationRetryException} (raised by a client when the
 * response hits {@code stop_reason=max_tokens}), the client has already bumped
 * the outgoing message's {@code requestedOutputTokens} to the model ceiling;
 * because this job keeps the thinker's conversation across attempts, the
 * dispatcher's one-time re-run reserves and sends the escalated budget.
 *
 * <p><b>Self-correction:</b> a response that fails to parse, or that parses but
 * fails the handler's required-field validation, is fed back to the model by
 * {@link ResponseCorrection}, the same protocol a one-call tool runs through
 * {@code AbstractModelDependentTool.converse}: a correction turn on the conversation and
 * a {@link ResponseCorrectionRetryException} for the dispatcher to re-run this same
 * instance, under a budget of {@value #MAX_CORRECTIONS} corrections per job.
 *
 * @param <T> The response type determined by the last message's ResponseHandler
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-22)
 */
@DisplayName(value = "Thinking", action = "Thinking")
public class LLMCall<T> extends AbstractJob<T> implements ModelDependent {
    /** Correction budget per job: at most this many parse/validation feedback retries. */
    public static final int MAX_CORRECTIONS = ResponseCorrection.MAX_CORRECTIONS;
    private final ConversationContext conversation;
    private ModelSpec pinned;
    /** The correction protocol and its budget; survives dispatcher re-runs of this same instance. */
    private final ResponseCorrection correction = new ResponseCorrection();

    /**
     * The model is the RESOLUTION's own, never an argument: this job declares the
     * conversation's grade and depth via {@code requireModel}, wires the returned binding
     * to the conversation, and the harness resolves it before execution - so the
     * rate-limiter reservation, the output budget, the wire ceiling and the truncation
     * ceiling all read one resolved cell and cannot silently diverge.
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

    @Override
    public Grade getGrade() {
        return conversation.getGrade();
    }

    @Override
    public void setGrade(Grade grade) {
        conversation.setGrade(grade);
    }

    @Override
    public void pinModel(ModelSpec model) {
        this.pinned = model;
    }

    @Override
    public ModelSpec pinnedModel() {
        return pinned;
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
        conversation.setModelBinding(requireSeat(req, conversation.resolveDepth()));
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        return req;
    }

    @Override
    public T execute(JobResources resources, JobContext<T> context) throws Exception {
        return correction.exchange(client(resources), conversation);
    }

    /** The resolved client. Package-private so the correction loop can be asserted without a transport. */
    LLMClient client(JobResources resources) {
        return resources.getLLMClient(conversation.getModel());
    }
}
