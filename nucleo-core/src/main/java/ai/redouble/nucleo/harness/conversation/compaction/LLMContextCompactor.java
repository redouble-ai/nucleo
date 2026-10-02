/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation.compaction;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Intelligent context compactor using LLMs for semantic-aware conversation summarization.
 * <p>
 * Employs message segmentation, age-weighted compression, and context-aware
 * summarization to reduce conversation size while preserving meaning and continuity.
 * <p>
 * Failure discipline follows the level. LIGHT, MODERATE and AGGRESSIVE are best-effort:
 * a summary job that fails keeps the original messages (logged with the failure), the
 * conversation is never left worse than it arrived, and the ladder above decides what
 * to try next. MAXIMUM is the last line before an overflow refusal, so its failures are
 * not degradable: they propagate as LLM-readable exceptions. An interrupt is never
 * best-effort - a dying turn must stop compacting, so it propagates from every level.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-15)
 */
public class LLMContextCompactor implements ContextCompactor {
    private static final Logger log = LoggerFactory.getLogger(LLMContextCompactor.class);
    private final Grade compactionGrade;
    private final Identifiable parent;

    /**
     * Creates a compactor using the small/fast model for cheap compaction.
     *
     * @param parent the parent identity for workflow lineage (required)
     */
    public LLMContextCompactor(Identifiable parent) {
        if (parent == null) {
            throw new IllegalArgumentException("Parent identity required for LLMContextCompactor");
        }
        this.compactionGrade = Grade.SMALL;
        this.parent = parent;
    }

    /**
     * Creates a compactor whose compaction calls run at the given grade.
     *
     * @param grade  the capability floor the compaction calls are resolved at
     * @param parent the parent identity for workflow lineage (required)
     */
    public LLMContextCompactor(Grade grade, Identifiable parent) {
        if (parent == null) {
            throw new IllegalArgumentException("Parent identity required for LLMContextCompactor");
        }
        this.compactionGrade = grade;
        this.parent = parent;
    }

    @Override
    public ConversationContext compact(ConversationContext context, CompactionLevel level) {
        List<Message> messages = context.getMessages();

        // Nothing to compact
        if (messages.size() < 3) {
            log.debug("Too few messages to compact: {}", messages.size());
            return context;
        }

        // Find last outgoing message - ALWAYS preserve
        int lastOutgoingIdx = findLastOutgoingMessageIndex(messages);
        if (lastOutgoingIdx < 0) {
            log.debug("No outgoing messages found, skipping compaction");
            return context;
        }

        log.info("Starting {} compaction of {} messages", level, messages.size());
        switch (level) {
        case LIGHT:
            compactLight(context, lastOutgoingIdx);
            break;
        case MODERATE:
            compactModerate(context, lastOutgoingIdx);
            break;
        case AGGRESSIVE:
            // Best-effort: a failed collapse keeps the originals, the ladder decides what
            // is next. Interrupts are re-raised inside compactionFailure and never eaten.
            try {
                compactAggressive(context, lastOutgoingIdx);
            }
            catch (Exception e) {
                RuntimeException surfaced = compactionFailure(level, e);
                if (e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                    throw surfaced;
                }
                log.warn("AGGRESSIVE compaction failed, keeping the original messages", surfaced);
            }
            break;
        case MAXIMUM:
            // @TODO gh-1: MAXIMUM currently runs the same collapse as AGGRESSIVE and
            // guarantees nothing beyond it. The real implementation - whatever it may
            // touch that AGGRESSIVE may not, so that ensureFits can guarantee a fit -
            // is an open design question tracked there. The failure contract is already
            // final: MAXIMUM is not best-effort, its failures propagate.
            try {
                compactAggressive(context, lastOutgoingIdx);
            }
            catch (Exception e) {
                throw compactionFailure(level, e);
            }
            break;
        }
        log.info("Compaction complete. Messages: {} -> {}", messages.size(), context.getMessages().size());
        return context;
    }

    /**
     * Types a compaction failure for propagation: an interrupt re-raises the flag and
     * every LLM-readable cause travels as itself (the checked branches re-wrapped in
     * their runtime siblings, since {@link #compact} declares no checked throws), so the
     * thinker loop upstream recognizes what happened instead of meeting a raw wrapper.
     */
    private static RuntimeException compactionFailure(CompactionLevel level, Exception e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new UncorrectableRuntimeLLMException("Interrupted during " + level + " compaction", cause);
        }
        if (cause instanceof RuntimeException runtime && runtime instanceof LLMReadable) {
            return runtime;
        }
        if (cause instanceof CorrectableLLMException correctable) {
            return new CorrectableRuntimeLLMException(correctable.getLLMMessage(), correctable);
        }
        if (cause instanceof UncorrectableLLMException uncorrectable) {
            return new UncorrectableRuntimeLLMException(uncorrectable.getLLMMessage(), uncorrectable);
        }
        return new UncorrectableRuntimeLLMException(level + " compaction failed: " + cause.getMessage(), cause);
    }

    /**
     * LIGHT compaction: Preserves conversation flow while condensing tool execution data.
     * Targets raw tool results which often contain verbose output, replacing them with
     * concise summaries that retain actionable information.
     * Respects per-message compactability - non-compactable messages are preserved verbatim.
     */
    private void compactLight(ConversationContext context, int lastOutgoingIdx) {
        List<Message> messages = context.getMessages();

        // Identify segments containing tool results
        List<MessageSegment> segments = identifyMessageSegments(messages, lastOutgoingIdx);

        // Create compaction tasks for tool results only (skip non-compactable messages)
        List<SegmentTask> tasks = new ArrayList<>();
        for (MessageSegment segment : segments) {
            if (segment.hasCompactableToolResults(messages)) {
                // Create task to compact just the compactable tool results in this segment
                tasks.add(new SegmentTask(segment, CompactionType.TOOL_RESULTS_ONLY));
            }
        }

        if (tasks.isEmpty()) {
            log.debug("No compactable tool results to compact in LIGHT mode");
            return;
        }

        // Execute compaction in parallel
        Map<Integer, String> compactionResults = executeParallelCompaction(tasks, messages);

        // Build new message list with compacted tool results
        List<Message> compacted = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            // Only apply compaction to compactable messages
            if (compactionResults.containsKey(i) && msg.isCompactable()) {
                // Use compacted version
                compacted.add(createCompactedMessage(msg, compactionResults.get(i)));
            }
            else {
                // Keep original (either not in results or non-compactable)
                compacted.add(msg);
            }
        }

        // Replace messages in place
        messages.clear();
        messages.addAll(compacted);
    }

    /**
     * MODERATE compaction: Age-weighted summarization mimicking human memory patterns.
     * Recent interactions remain detailed while older content progressively fades to
     * essential facts, maintaining narrative continuity across the conversation.
     * Respects per-message compactability - non-compactable messages are preserved verbatim.
     */
    private void compactModerate(ConversationContext context, int lastOutgoingIdx) {
        List<Message> messages = context.getMessages();

        // Identify all segments
        List<MessageSegment> segments = identifyMessageSegments(messages, lastOutgoingIdx);

        // Create compaction tasks only for segments that have compactable content
        List<SegmentTask> tasks = new ArrayList<>();
        for (MessageSegment segment : segments) {
            if (segment.hasCompactableMessages(messages)) {
                // Compact entire segment, with age-based aggressiveness
                tasks.add(new SegmentTask(segment, CompactionType.FULL_SEGMENT));
            }
        }

        // Execute compaction in parallel
        Map<Integer, String> compactionResults = executeParallelCompaction(tasks, messages);

        // Build new message list preserving non-compactable messages
        List<Message> compacted = new ArrayList<>();
        Set<Integer> processedIndices = new HashSet<>();

        for (MessageSegment segment : segments) {
            // First, add any non-compactable messages from this segment verbatim
            for (int idx : segment.messageIndices) {
                Message msg = messages.get(idx);
                if (!msg.isCompactable()) {
                    compacted.add(msg);
                    processedIndices.add(idx);
                }
            }

            // Add compacted version of compactable messages in segment
            if (segment.compactedText != null && segment.hasCompactableMessages(messages)) {
                Message segmentSummary = createSegmentSummary(segment);
                compacted.add(segmentSummary);
                // Mark compactable messages as processed
                for (int idx : segment.messageIndices) {
                    if (messages.get(idx).isCompactable()) {
                        processedIndices.add(idx);
                    }
                }
            }

            // Add the incoming response after this segment
            if (segment.incomingResponse != null) {
                compacted.add(segment.incomingResponse);
            }
        }

        // Add any messages not in segments (including last outgoing)
        for (int i = 0; i < messages.size(); i++) {
            if (!processedIndices.contains(i) && i >= lastOutgoingIdx) {
                compacted.add(messages.get(i));
            }
        }

        // Replace messages in place
        messages.clear();
        messages.addAll(compacted);
    }

    /**
     * AGGRESSIVE compaction: Distills entire conversation to its essential narrative.
     * Creates a comprehensive summary capturing key decisions, findings, and progression
     * while preserving only the immediate context for continuity.
     * Respects per-message compactability - non-compactable messages are preserved verbatim.
     */
    private void compactAggressive(ConversationContext context, int lastOutgoingIdx) throws Exception {
        List<Message> messages = context.getMessages();
        Message lastOutgoing = messages.get(lastOutgoingIdx);

        // Separate non-compactable messages (preserve verbatim) from compactable ones (summarize)
        List<Message> nonCompactable = new ArrayList<>();
        List<Message> toSummarize = new ArrayList<>();
        for (int i = 0; i < lastOutgoingIdx; i++) {
            Message msg = messages.get(i);
            if (msg.isCompactable()) {
                toSummarize.add(msg);
            }
            else {
                nonCompactable.add(msg);
            }
        }

        // If nothing to summarize, just keep non-compactable messages and last outgoing
        if (toSummarize.isEmpty()) {
            messages.clear();
            messages.addAll(nonCompactable);
            messages.add(lastOutgoing);
            return;
        }

        // Create single summary of compactable messages using LLM
        String summaryPrompt = buildAggressiveSummaryPrompt(toSummarize);
        String summary = summarize(summaryPrompt, CompactionType.AGGRESSIVE_SUMMARY);

        // Create summary message (user role - summary derives from untrusted content, must not be system)
        OutgoingMessage<String> summaryMsg = new OutgoingMessage<>(StringResponseHandler.instance);
        summaryMsg.setTimestamp(Instant.now());
        summaryMsg.addText(String.format("""
                === CONVERSATION SUMMARY ===
                Summary of %d compacted messages:
                
                %s
                === END SUMMARY ===""", toSummarize.size(), summary));
        summaryMsg.setCache(true);

        // Handle last outgoing - only compact if compactable
        Message finalLastOutgoing = lastOutgoing;
        if (lastOutgoing.isCompactable() && isToolResult(lastOutgoing)) {
            // Summarize tool result separately
            String toolPrompt = buildToolSummaryPrompt(lastOutgoing);
            String toolSummary = summarize(toolPrompt, CompactionType.TOOL_RESULT_SUMMARY);
            OutgoingMessage<String> compactedTool = new OutgoingMessage<>(StringResponseHandler.instance);
            compactedTool.setRole(lastOutgoing.getRole());
            compactedTool.setTimestamp(lastOutgoing.getTimestamp());
            compactedTool.addText("[Recent Tool Result]: " + toolSummary);
            compactedTool.setCache(true);
            finalLastOutgoing = compactedTool;
        }

        // Replace all messages: non-compactable first (in order), then summary, then last outgoing
        messages.clear();
        messages.addAll(nonCompactable);
        messages.add(summaryMsg);
        messages.add(finalLastOutgoing);
    }

    /**
     * Segments conversation into logical units based on interaction patterns.
     * Groups related tool calls and their results, preserving causal relationships
     * and execution flow for coherent summarization.
     */
    private List<MessageSegment> identifyMessageSegments(List<Message> messages, int lastOutgoingIdx) {
        List<MessageSegment> segments = new ArrayList<>();
        MessageSegment currentSegment = null;

        for (int i = 0; i < lastOutgoingIdx; i++) {
            Message msg = messages.get(i);

            if (msg instanceof OutgoingMessage) {
                // Add to current segment or start new one
                if (currentSegment == null) {
                    currentSegment = new MessageSegment();
                    currentSegment.startIdx = i;
                }
                currentSegment.outgoingMessages.add(msg);
                currentSegment.messageIndices.add(i);

                // Check if it's a tool result
                if (isToolResult(msg)) {
                    currentSegment.toolResultIndices.add(i);
                }

            }
            else if (msg instanceof IncomingMessage) {
                // End current segment if exists
                if (currentSegment != null) {
                    currentSegment.endIdx = i;
                    currentSegment.incomingResponse = msg;
                    currentSegment.calculateAgeWeight(i, messages.size());
                    segments.add(currentSegment);
                    currentSegment = null;
                }
            }
        }

        // Handle last segment if no incoming message follows
        if (currentSegment != null) {
            currentSegment.endIdx = lastOutgoingIdx;
            currentSegment.calculateAgeWeight(lastOutgoingIdx, messages.size());
            segments.add(currentSegment);
        }

        return segments;
    }

    /**
     * Orchestrates parallel compaction with context awareness.
     * Each segment is processed with surrounding context to maintain coherence
     * while leveraging parallel execution for performance.
     */
    Map<Integer, String> executeParallelCompaction(List<SegmentTask> tasks, List<Message> messages) {

        if (tasks.isEmpty()) {
            return Collections.emptyMap();
        }

        // Get job dispatcher
        JobDispatcher dispatcher = JobDispatcher.getInstance();

        // Submit all compaction jobs in parallel
        Map<SegmentTask, JobHandle<CompactionResult>> handles = new HashMap<>();

        for (SegmentTask task : tasks) {
            // Extract context window for this task
            List<Message> contextWindow = extractContextWindow(messages, task.segment().startIdx);

            // Create and submit job
            CompactionJob job = new CompactionJob(this.parent, task, contextWindow, messages, compactionGrade);
            JobHandle<CompactionResult> handle = dispatcher.submit(job);
            handles.put(task, handle);
        }

        log.debug("Submitted {} compaction jobs in parallel", handles.size());

        // Collect results
        Map<Integer, String> results = new HashMap<>();
        for (Map.Entry<SegmentTask, JobHandle<CompactionResult>> entry : handles.entrySet()) {
            MessageSegment segment = entry.getKey().segment();
            try {
                CompactionResult result = entry.getValue().get();

                // Store compacted text in segment
                segment.compactedText = result.getSummary();

                // Map results to message indices
                if (entry.getKey().type() == CompactionType.TOOL_RESULTS_ONLY) {
                    // Only tool results get replaced
                    for (int idx : segment.toolResultIndices) {
                        results.put(idx, result.getSummary());
                    }
                }
                else {
                    // Entire segment gets replaced
                    for (int idx : segment.messageIndices) {
                        results.put(idx, result.getSummary());
                    }
                }

            }
            catch (InterruptedException e) {
                // An interrupt is never best-effort: the turn is dying and must stop compacting
                Thread.currentThread().interrupt();
                throw new UncorrectableRuntimeLLMException("Interrupted while collecting compaction summaries", e);
            }
            catch (Exception e) {
                // Best-effort by contract: this segment keeps its original messages, the
                // failure surfaces in the log with its cause, and the ladder decides what
                // is next. MAXIMUM never reaches this path (it fails loudly in compact).
                log.warn("Compaction job failed for segment {}-{}, keeping the original messages", segment.startIdx, segment.endIdx, e);
            }
        }

        return results;
    }

    /**
     * Extracts a context window around a target index.
     */
    private List<Message> extractContextWindow(List<Message> messages, int targetIdx) {
        int start = Math.max(0, targetIdx - 5);
        int end = Math.min(messages.size(), targetIdx + 5);
        return new ArrayList<>(messages.subList(start, end));
    }

    /**
     * Creates a compacted message from original with new content.
     */
    private Message createCompactedMessage(Message original, String compactedContent) {
        if (original instanceof IncomingMessage) {
            IncomingMessage<String> msg = new IncomingMessage<>(StringResponseHandler.instance);
            msg.overwriteRawContent(compactedContent);
            msg.setTimestamp(original.getTimestamp());
            msg.setCache(true);
            return msg;
        }
        else {
            OutgoingMessage<String> msg = new OutgoingMessage<>(StringResponseHandler.instance);
            msg.setRole(original.getRole());
            msg.addText(compactedContent);
            msg.setTimestamp(original.getTimestamp());
            msg.setCache(true);
            return msg;
        }
    }

    /**
     * Creates a summary message for a segment.
     */
    private Message createSegmentSummary(MessageSegment segment) {
        OutgoingMessage<String> msg = new OutgoingMessage<>(StringResponseHandler.instance);
        msg.setTimestamp(Instant.now());
        msg.addText(segment.compactedText);
        msg.setCache(true);
        return msg;
    }

    /**
     * Builds prompt for aggressive single summary. Package-private so tests can pin
     * the prompt contract without an LLM.
     */
    String buildAggressiveSummaryPrompt(List<Message> messages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Create a comprehensive summary of this conversation history.\n");
        prompt.append("Capture the key progression, important findings, and decisions made.\n");
        prompt.append("Preserve any artifact references like «artifact:type~uuid» (e.g., «artifact:link:cite~a1b2c3»).\n\n");

        prompt.append("Conversation:\n");
        prompt.repeat("=", 50).append("\n");

        for (Message msg : messages) {
            prompt.append("[").append(msg.getRole()).append("]: ");
            String content = msg.getRawContent();
            if (content != null && content.length() > 500) {
                content = content.substring(0, 500) + "...";
            }
            prompt.append(content).append("\n\n");
        }

        prompt.repeat("=", 50).append("\n");
        prompt.append("Provide a clear, concise summary:");

        return prompt.toString();
    }

    /**
     * Builds prompt for tool result summary.
     */
    private String buildToolSummaryPrompt(Message toolResult) {
        return String.format("""
                Summarize this tool result concisely, preserving key findings:
                
                %s
                
                Provide a brief summary:""", toolResult.getRawContent());
    }

    /**
     * One summary through a {@link CompactionJob}, the same door the parallel segment path
     * uses: resolved through the picker, reserved, admitted and recorded like every other LLM
     * call. A summary that comes back empty is a failed compaction, not a summary.
     */
    String summarize(String prompt, CompactionType type) throws Exception {
        CompactionJob job = new CompactionJob(this.parent, new SummaryTask(prompt, type), List.of(), List.of(), compactionGrade);
        return requireSummary(JobDispatcher.getInstance().submit(job).get());
    }

    /**
     * The blank-summary refusal: an empty answer is a failed compaction, never a summary -
     * inserting it would REPLACE content with nothing. Package-private so the check is
     * testable without dispatching a job.
     */
    static String requireSummary(CompactionResult result) {
        if (result.getSummary() == null || result.getSummary().isBlank()) {
            throw new UncorrectableRuntimeLLMException("Compaction LLM returned no summary - cannot compact the context");
        }
        return result.getSummary();
    }

    /**
     * Checks if a message is a tool result.
     */
    private boolean isToolResult(Message msg) {
        if (!(msg instanceof OutgoingMessage))
            return false;
        String content = msg.getRawContent();
        return content != null && content.contains("[") && content.contains("result]:") && (content.contains("{") || content.contains("```"));
    }

    /**
     * Finds the index of the last outgoing message.
     */
    private int findLastOutgoingMessageIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof OutgoingMessage) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Represents a segment of messages between incoming messages.
     */
    static class MessageSegment {
        int startIdx;
        int endIdx;
        final List<Message> outgoingMessages = new ArrayList<>();
        final List<Integer> messageIndices = new ArrayList<>();
        final List<Integer> toolResultIndices = new ArrayList<>();
        Message incomingResponse;
        double ageWeight;
        String compactedText;

        /**
         * Checks if this segment has any compactable tool results.
         * Only compactable messages should be considered for compaction.
         */
        boolean hasCompactableToolResults(List<Message> allMessages) {
            for (int idx : toolResultIndices) {
                if (allMessages.get(idx).isCompactable()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Checks if this segment has any compactable messages.
         */
        boolean hasCompactableMessages(List<Message> allMessages) {
            for (int idx : messageIndices) {
                if (allMessages.get(idx).isCompactable()) {
                    return true;
                }
            }
            return false;
        }

        void calculateAgeWeight(int position, int total) {
            this.ageWeight = (double)position / total;
        }
    }


    /**
     * What one {@link CompactionJob} compacts: a segment, whose prompt the job composes from
     * the segment and its surrounding window, or a prompt the compactor composed from the whole
     * conversation.
     */
    sealed interface CompactionTask permits SegmentTask, SummaryTask {
        CompactionType type();
    }


    /**
     * A segment of the conversation, compacted as {@link CompactionType#TOOL_RESULTS_ONLY} or {@link CompactionType#FULL_SEGMENT}.
     */
    record SegmentTask(MessageSegment segment, CompactionType type) implements CompactionTask {
    }


    /**
     * A prompt already composed, for {@link CompactionType#AGGRESSIVE_SUMMARY} and {@link CompactionType#TOOL_RESULT_SUMMARY}.
     */
    record SummaryTask(String prompt, CompactionType type) implements CompactionTask {
    }


    /**
     * Types of compaction.
     */
    enum CompactionType {
        TOOL_RESULTS_ONLY,     // LIGHT mode - only compact tool results
        FULL_SEGMENT,          // MODERATE mode - compact entire segment
        AGGRESSIVE_SUMMARY,    // AGGRESSIVE mode - one narrative of every compactable message
        TOOL_RESULT_SUMMARY    // AGGRESSIVE mode - the last outgoing tool result on its own
    }
}