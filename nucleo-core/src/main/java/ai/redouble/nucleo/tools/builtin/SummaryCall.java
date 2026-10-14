/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;

import java.time.*;

/**
 * One summarization exchange: a text rendered into the summarization prompt at a
 * {@link SummarySize}, sent to the model the seat resolves and answered as plain text, trimmed.
 * {@link SummarizationTool} runs one for text that fits the model, and when it does not, one
 * per chunk and one over the chunk summaries. Whether the text fits is judged against the
 * resolved model, because only its tokenizer and window say so: text over the model's share,
 * the window less {@link #CONTEXT_RESERVE}, fails the call before anything is sent, as
 * {@link TokenEstimateExceedsLimitException}; text past the window itself is refused earlier
 * still, when the binding prices the conversation. Either way the summarizer reads the model
 * this call resolved and chunks the text by it.
 *
 * <p>The size's backstop is the answer's {@code max_tokens}. It bounds runaway output and sits
 * well above what a steered summary produces, so the client's truncation escalation serves
 * only the genuine tail.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-13)
 */
class SummaryCall extends AbstractModelDependentTool<Void, String> {
    /** The share of the model's window kept free of the text, for the prompt around it and the answer. */
    static final double CONTEXT_RESERVE = 0.2;
    private final String text;
    private final SummarySize size;
    private final String textContext;
    private ModelBinding binding;

    /**
     * @param parent      the summarizer running this call
     * @param grade       the grade the call resolves at when it is not pinned
     * @param pinned      the entry the call runs on, or null to resolve the grade
     * @param text        the text to summarize
     * @param size        the size the summary is steered to
     * @param textContext what the text is, for the prompt, or null
     */
    SummaryCall(Identifiable parent, Grade grade, ModelSpec pinned, String text, SummarySize size, String textContext) {
        super(parent, "SummaryCall", grade);
        // 6 min: hung-call guard for one LLM call - summarizing a long text can run far past the
        // typical few seconds. A ceiling, not expected latency (matches LLMCall).
        setTimeout(Duration.ofMinutes(6));
        if (pinned != null) {
            pinModel(pinned);
        }
        this.text = text;
        this.size = size;
        this.textContext = textContext;
    }

    @Override
    public JobType getJobType() {
        return JobType.UTILITY;
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        binding = wireConversation(req, Depth.IMMEDIATE, this::buildConversation);
        req.setRequiresTransaction(false);
        return req;
    }

    private ConversationContext buildConversation() {
        ConversationContext built = new ConversationContext();
        built.setGrade(pinnedModel() != null ? pinnedModel().getGrade() : getGrade());
        built.setDepth(Depth.IMMEDIATE);
        built.setOutputDeclaration(OutputDeclaration.of(SummarizationTool.sizeToBackstopTokens(size)));
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText(SummarizationTool.buildPrompt(text, size, textContext));
        built.getMessages().add(message);
        return built;
    }

    /** The most text the model takes in one call: its window less {@link #CONTEXT_RESERVE}. */
    static int maxTextTokens(ModelSpec model) {
        return (int) (model.getMaxContextTokens() * (1 - CONTEXT_RESERVE));
    }

    /**
     * The model this call resolved, which the summarizer chunks by when the text did not fit
     * it; null while the seat is unresolved, as it is when the call never reached execution.
     */
    ModelSpec model() {
        return binding != null && binding.isResolved() ? binding.getModel() : null;
    }

    @Override
    public String execute(JobResources resources, JobContext<String> context) throws LLMReadableCheckedException {
        ModelSpec model = model();
        int textTokens = model.tokenCounter().countTokens(text);
        int maxTextTokens = maxTextTokens(model);
        if (textTokens > maxTextTokens) {
            throw new TokenEstimateExceedsLimitException(textTokens, maxTextTokens, model.getId());
        }
        try {
            String summary = converse(resources);
            return summary.trim();
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }
}
