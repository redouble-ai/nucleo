/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation.compaction;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;

import java.time.*;
import java.util.*;

/**
 * Job for parallel execution of conversation compaction tasks.
 * Each job compacts a segment of messages using an LLM. Built only by
 * {@link LLMContextCompactor}, whose tasks it takes.
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
     * @param task the compaction task with segment to compact
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
     * whole conversation; a segment task's is composed here from the segment and its window.
     * Package-private so tests can pin the prompt contract without an LLM.
     */
    String buildPrompt() {
        return switch (task) {
            case LLMContextCompactor.SummaryTask summary -> summary.prompt();
            case LLMContextCompactor.SegmentTask segmentTask -> buildSegmentPrompt(segmentTask.segment(), segmentTask.type());
        };
    }

    private String buildSegmentPrompt(LLMContextCompactor.MessageSegment segment, LLMContextCompactor.CompactionType type) {
        StringBuilder prompt = new StringBuilder();

        // Add context window (messages before and after)
        if (!contextWindow.isEmpty()) {
            prompt.append("Previous conversation context:\n");
            prompt.repeat("-", 50).append("\n");

            for (Message msg : contextWindow) {
                // Skip the messages we're actually compacting
                if (!segment.messageIndices.contains(allMessages.indexOf(msg))) {
                    String role = msg instanceof IncomingMessage ? "assistant" : msg.getRole();
                    String content = truncate(msg.getRawContent(), 300);
                    prompt.append("[").append(role).append("]: ").append(content).append("\n");
                }
            }
            prompt.repeat("-", 50).append("\n\n");
        }

        // Build specific prompt based on compaction type
        return switch (type) {
            case TOOL_RESULTS_ONLY -> buildToolResultsPrompt(prompt, segment);
            case FULL_SEGMENT -> buildFullSegmentPrompt(prompt, segment);
            default -> throw new IllegalArgumentException("A segment task cannot be of type " + type);
        };
    }

    /**
     * Builds prompt for compacting tool results only (LIGHT mode).
     */
    private String buildToolResultsPrompt(StringBuilder base, LLMContextCompactor.MessageSegment segment) {
        base.append("A series of tools were executed. ");

        // If we have the assistant's response, mention it
        if (segment.incomingResponse != null) {
            base.append("The assistant then responded based on these results.\n\n");

            base.append("Assistant's response (shows what was important):\n");
            String assistantResponse = truncate(segment.incomingResponse.getRawContent(), 500);
            base.append(assistantResponse).append("\n\n");
        } else {
            base.append("\n\n");
        }

        base.append("Tool results to summarize:\n");
        base.repeat("=", 50).append("\n");

        // Add all tool results from this segment
        for (Message msg : segment.outgoingMessages) {
            if (isToolResult(msg)) {
                String toolName = extractToolName(msg.getRawContent());
                if (toolName != null) {
                    base.append("[").append(toolName).append(" Result]:\n");
                } else {
                    base.append("[Tool Result]:\n");
                }
                base.append(msg.getRawContent()).append("\n\n");
            }
        }

        base.repeat("=", 50).append("\n\n");

        // Age-based instructions
        appendAgeInstructions(base, segment);

        base.append("Instructions:\n");
        base.append("- Create a concise summary of what these tools found\n");
        base.append("- If the assistant's response indicates certain results were not useful, mention that briefly\n");
        base.append("- CRITICAL: Preserve any artifact references like «artifact:type~uuid» (e.g., «artifact:link:cite~a1b2c3»)\n");
        base.append("- Focus on the key findings\n\n");

        base.append("Return your response as JSON with a 'summary' field containing the compacted tool results.");

        return base.toString();
    }

    /**
     * Builds prompt for compacting an entire segment (MODERATE mode).
     */
    private String buildFullSegmentPrompt(StringBuilder base, LLMContextCompactor.MessageSegment segment) {
        base.append("Summarize this conversation segment.\n\n");

        // Messages to summarize
        base.append("Messages in this segment:\n");
        base.repeat("=", 50).append("\n");

        for (Message msg : segment.outgoingMessages) {
            String role = msg.getRole();
            if (isToolResult(msg)) {
                String toolName = extractToolName(msg.getRawContent());
                role = toolName != null ? toolName : "tool";
            }
            base.append("[").append(role).append("]: ");
            base.append(msg.getRawContent()).append("\n\n");
        }

        base.repeat("=", 50).append("\n\n");

        // Include assistant's response to understand importance
        if (segment.incomingResponse != null) {
            base.append("Assistant's response (shows what was important):\n");
            base.append(segment.incomingResponse.getRawContent()).append("\n\n");
        }

        // Age-based instructions
        appendAgeInstructions(base, segment);

        base.append("Instructions:\n");
        base.append("- Create a single coherent summary of this entire segment\n");
        base.append("- Look at the assistant's response to understand what was important\n");
        base.append("- CRITICAL: Preserve any artifact references like «artifact:type~uuid» (e.g., «artifact:link:cite~a1b2c3»)\n");
        base.append("- Be concise but preserve key information\n\n");

        base.append("Return your response as JSON with a 'summary' field containing the segment summary.");

        return base.toString();
    }

    /**
     * Appends age-based compaction instructions.
     */
    private void appendAgeInstructions(StringBuilder prompt, LLMContextCompactor.MessageSegment segment) {
        double age = segment.ageWeight;

        if (age < 0.25) {
            prompt.append("This is in the OLDEST 25% of the conversation - be VERY concise (1-2 lines max).\n\n");
        } else if (age < 0.5) {
            prompt.append("This is in the older half of the conversation - be concise (2-3 lines).\n\n");
        } else if (age < 0.75) {
            prompt.append("This is relatively recent - moderate detail (3-5 lines).\n\n");
        } else {
            prompt.append("This is recent - preserve most details.\n\n");
        }
    }

    /**
     * Checks if a message is a tool result.
     */
    private boolean isToolResult(Message msg) {
        if (!(msg instanceof OutgoingMessage)) return false;
        String content = msg.getRawContent();
        return content != null &&
               content.contains("[") &&
               content.contains("result]:") &&
               (content.contains("{") || content.contains("```"));
    }

    /**
     * Extracts tool name from a tool result message.
     */
    private String extractToolName(String content) {
        if (content == null) return null;

        // Look for pattern like "[ToolName result]:"
        int startIdx = content.indexOf("[");
        int endIdx = content.indexOf(" result]:");

        if (startIdx >= 0 && endIdx > startIdx) {
            return content.substring(startIdx + 1, endIdx);
        }

        return null;
    }

    /**
     * Truncates text to specified length.
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}