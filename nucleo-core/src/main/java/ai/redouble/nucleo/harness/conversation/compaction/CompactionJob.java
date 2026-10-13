/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation.compaction;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.tokens.*;

import java.time.*;
import java.util.*;

/**
 * Job for parallel execution of conversation compaction tasks.
 * Each job compacts one tool result, one exchange, or a composed prompt, using an LLM.
 * Built only by {@link LLMContextCompactor}, whose tasks it takes. What the summary model
 * reads is the text a model reads ({@link ModelText}), never the placeholder form.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-19)
 */
class CompactionJob extends AbstractJob<CompactionResult> {
    private final LLMContextCompactor.CompactionTask task;
    private final List<Message> contextWindow;
    private final List<Message> allMessages;
    private final Grade grade;
    private ModelBinding binding;
    /** Built once in {@link #getRequirements}, kept across attempts, re-wired per attempt; package-private so tests can pin that lifecycle. */
    ConversationContext conversation;

    /**
     * Creates a compaction job.
     *
     * @param parent the parent identity for lineage tracking
     * @param task the compaction task
     * @param contextWindow surrounding messages for context
     * @param allMessages all messages in the conversation (for reference)
     * @param grade the capability floor the compaction call is resolved at
     */
    CompactionJob(Identifiable parent,
                  LLMContextCompactor.CompactionTask task,
                  List<Message> contextWindow,
                  List<Message> allMessages,
                  Grade grade) {
        super(parent, "CompactionJob");
        // 6 min: hung-call guard for one LLM call - compacting a large context can run far past the
        // typical few seconds. A ceiling, not expected latency (matches LLMCall).
        setTimeout(Duration.ofMinutes(6));
        this.task = task;
        this.contextWindow = contextWindow;
        this.allMessages = allMessages;
        this.grade = grade;
    }

    @Override
    public JobType getJobType() {
        return JobType.UTILITY;
    }

    /**
     * Builds the one conversation this job sends, once, and wires a fresh binding onto it per
     * attempt: the prompt exists at construction, so the wired form prices the exact input
     * under the resolved tokenizer, and a retained conversation lets the client's truncation
     * escalation reach the re-run. A compaction summary distils an arbitrarily large segment
     * into structured prose: the typical structured answer.
     */
    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        if (conversation == null) {
            ConversationContext built = new ConversationContext();
            built.setGrade(grade);
            built.setDepth(Depth.STANDARD);
            built.setOutputDeclaration(OutputDeclaration.of(OutputSize.STANDARD));
            OutgoingMessage<CompactionResult> msg = new OutgoingMessage<>(new PojoResponseHandler<>(CompactionResult.class));
            msg.setRole("user");
            msg.addText(buildPrompt());
            built.getMessages().add(msg);
            conversation = built;
        }
        binding = req.requireModel(grade, Depth.STANDARD);
        conversation.setModelBinding(binding);
        return req;
    }

    @Override
    public CompactionResult execute(JobResources resources, JobContext<CompactionResult> context) throws Exception {
        LLMClient llm = resources.getLLMClient(binding.getModel());
        LLMResponse<CompactionResult> response = llm.singleResponse(new LLMRequest<>(conversation));

        // Extract the result
        CompactionResult result = response.getResponseMessage().getResponse();

        if (result == null) {
            // Fallback if parsing failed
            String rawResponse = response.getResponseMessage().getRawContent();
            result = new CompactionResult();
            result.setSummary(rawResponse);
        }

        return result;
    }

    /**
     * The prompt this job sends: a summary task carries one the compactor composed from the
     * whole conversation; a tool result's or an exchange's is composed here from the task and
     * its surrounding window. Package-private so tests can pin the prompt contract without an LLM.
     */
    String buildPrompt() {
        return switch (task) {
            case LLMContextCompactor.SummaryTask summary -> summary.prompt();
            case LLMContextCompactor.ToolResultTask toolResult -> buildToolResultPrompt(withContext(toolResult.exchange()), toolResult);
            case LLMContextCompactor.ExchangeTask exchangeTask -> buildExchangePrompt(withContext(exchangeTask.exchange()), exchangeTask.exchange());
        };
    }

    /** The surrounding messages, clipped, so the summary model knows where the exchange sits. */
    private StringBuilder withContext(LLMContextCompactor.Exchange exchange) {
        StringBuilder prompt = new StringBuilder();
        if (contextWindow.isEmpty()) {
            return prompt;
        }
        Set<Message> own = Collections.newSetFromMap(new IdentityHashMap<>());
        own.addAll(exchange.all());
        prompt.append("Previous conversation context:\n");
        prompt.repeat("-", 50).append("\n");
        for (Message msg : contextWindow) {
            if (!own.contains(msg)) {
                prompt.append("[").append(msg.getRole()).append("]: ").append(truncate(ModelText.render(msg), 300)).append("\n");
            }
        }
        prompt.repeat("-", 50).append("\n\n");
        return prompt;
    }

    /**
     * Builds the prompt for one tool result (LIGHT): the call that asked for it, the payload it
     * returned, and the assistant's next turn when there is one, which shows what mattered.
     */
    private String buildToolResultPrompt(StringBuilder base, LLMContextCompactor.ToolResultTask task) {
        LLMContextCompactor.Exchange exchange = task.exchange();
        String toolName = exchange.toolName(task.result().toolUseId());
        base.append("A tool was called and returned a result.\n\n");
        if (exchange.assistant != null) {
            base.append("The call:\n").append(ModelText.render(exchange.assistant)).append("\n\n");
        }
        base.append("The result to summarize");
        if (toolName != null) {
            base.append(" (").append(toolName).append(")");
        }
        base.append(":\n");
        base.repeat("=", 50).append("\n");
        base.append(task.result().resultJson()).append("\n");
        base.repeat("=", 50).append("\n\n");
        appendAgeInstructions(base, exchange);
        base.append("Instructions:\n");
        base.append("- Create a concise summary of what this tool found\n");
        base.append("- CRITICAL: Preserve any artifact references like «artifact:type~uuid» (e.g., «artifact:link:cite~a1b2c3»)\n");
        base.append("- Focus on the key findings\n\n");
        base.append("Return your response as JSON with a 'summary' field containing the compacted tool result.");
        return base.toString();
    }

    /**
     * Builds the prompt for one exchange (MODERATE): the assistant's turn with the calls it made,
     * then every turn that answered it, each as the model read it.
     */
    private String buildExchangePrompt(StringBuilder base, LLMContextCompactor.Exchange exchange) {
        base.append("Summarize this conversation exchange.\n\n");
        base.append("Messages in this exchange:\n");
        base.repeat("=", 50).append("\n");
        for (Message msg : exchange.all()) {
            String role = msg.getRole();
            if (msg instanceof OutgoingMessage<?> outgoing && !exchange.compactable()) {
                // the opening: only the compactable messages are summarized
                if (!outgoing.isCompactable()) {
                    continue;
                }
            }
            base.append("[").append(role).append("]: ");
            base.append(ModelText.render(msg)).append("\n\n");
        }
        base.repeat("=", 50).append("\n\n");
        appendAgeInstructions(base, exchange);
        base.append("Instructions:\n");
        base.append("- Create a single coherent summary of this entire exchange\n");
        base.append("- Keep what the tool calls found and what the assistant concluded from it\n");
        base.append("- CRITICAL: Preserve any artifact references like «artifact:type~uuid» (e.g., «artifact:link:cite~a1b2c3»)\n");
        base.append("- Be concise but preserve key information\n\n");
        base.append("Return your response as JSON with a 'summary' field containing the exchange summary.");
        return base.toString();
    }

    /** Appends the terseness instruction for the exchange's age: the older, the shorter. */
    private void appendAgeInstructions(StringBuilder prompt, LLMContextCompactor.Exchange exchange) {
        double age = exchange.ageWeight;
        if (age < 0.25) {
            prompt.append("This is in the OLDEST 25% of the conversation - be VERY concise (1-2 lines max).\n\n");
        }
        else if (age < 0.5) {
            prompt.append("This is in the older half of the conversation - be concise (2-3 lines).\n\n");
        }
        else if (age < 0.75) {
            prompt.append("This is relatively recent - moderate detail (3-5 lines).\n\n");
        }
        else {
            prompt.append("This is recent - preserve most details.\n\n");
        }
    }

    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
