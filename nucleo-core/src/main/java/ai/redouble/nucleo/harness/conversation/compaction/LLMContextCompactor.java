/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation.compaction;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.tokens.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Context compactor using LLMs for semantic-aware conversation summarization.
 *
 * <p>The unit of compaction is the exchange: an assistant turn and the outgoing turns that
 * answer it, up to the next assistant turn. A tool call lives on the assistant turn and its
 * result on the turn that follows, and a provider refuses a request in which either stands
 * alone, so an exchange is compacted whole or kept whole, and either side marked
 * non-compactable keeps both. The messages before the first assistant turn form the opening
 * exchange, which has no pairing and is compacted message by message. The newest exchange is
 * the anchor: it is never touched below AGGRESSIVE, and at AGGRESSIVE it is summarized on its
 * own when it carries tool results, so the model keeps the latest interaction with recency.
 *
 * <p>Every level checks the list it built: no tool call left without its result and no result
 * without its call. An orphan on either side is a compactor bug and throws
 * {@link IllegalStateException} naming the id.
 *
 * <p>What the summary model reads is the text a model reads ({@link ModelText}): a tool call
 * with its input, a tool result with its payload. The summaries themselves are text; a LIGHT
 * summary of a tool result replaces the result's payload inside its block, so the block keeps
 * its id and the pairing with its call.
 *
 * <p>Failure discipline follows the level. LIGHT, MODERATE and AGGRESSIVE are best-effort:
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
        if (messages.size() < 3) {
            log.debug("Too few messages to compact: {}", messages.size());
            return context;
        }
        List<Exchange> exchanges = exchanges(messages);
        if (exchanges.isEmpty() || exchanges.getLast().followers.isEmpty()) {
            // no outgoing message to anchor on: nothing the ladder may touch
            log.debug("No outgoing messages found, skipping compaction");
            return context;
        }
        log.info("Starting {} compaction of {} messages in {} exchanges", level, messages.size(), exchanges.size());
        List<Message> compacted;
        switch (level) {
        case LIGHT:
            compacted = compactLight(messages, exchanges);
            break;
        case MODERATE:
            compacted = compactModerate(messages, exchanges);
            break;
        case AGGRESSIVE:
            // Best-effort: a failed collapse keeps the originals, the ladder decides what
            // is next. Interrupts are re-raised inside compactionFailure and never eaten.
            try {
                compacted = compactAggressive(messages, exchanges);
            }
            catch (Exception e) {
                RuntimeException surfaced = compactionFailure(level, e);
                if (e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                    throw surfaced;
                }
                log.warn("AGGRESSIVE compaction failed, keeping the original messages", surfaced);
                return context;
            }
            break;
        case MAXIMUM:
            // @TODO gh-1: MAXIMUM currently runs the same collapse as AGGRESSIVE and
            // guarantees nothing beyond it. The real implementation - whatever it may
            // touch that AGGRESSIVE may not, so that ensureFits can guarantee a fit -
            // is an open design question tracked there. The failure contract is already
            // final: MAXIMUM is not best-effort, its failures propagate.
            try {
                compacted = compactAggressive(messages, exchanges);
            }
            catch (Exception e) {
                throw compactionFailure(level, e);
            }
            break;
        default:
            throw new IllegalArgumentException("Unknown compaction level " + level);
        }
        requirePaired(compacted);
        messages.clear();
        messages.addAll(compacted);
        log.info("Compaction complete. Messages: {} -> {}", exchanges.stream().mapToInt(Exchange::size).sum(), messages.size());
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
     * LIGHT: every tool result in a compactable exchange before the anchor is summarized on its
     * own, and the summary replaces the payload inside the result's block. Nothing else moves;
     * every id, every pairing and every message stay where they were.
     */
    private List<Message> compactLight(List<Message> messages, List<Exchange> exchanges) {
        List<CompactionTask> tasks = new ArrayList<>();
        for (Exchange exchange : exchanges.subList(0, exchanges.size() - 1)) {
            if (!exchange.compactable()) {
                continue;
            }
            for (Message follower : exchange.followers) {
                if (follower instanceof OutgoingMessage<?> outgoing) {
                    for (ContentBlock block : outgoing.getContentBlocks()) {
                        if (block instanceof ToolResultBlock result) {
                            tasks.add(new ToolResultTask(exchange, outgoing, result));
                        }
                    }
                }
            }
        }
        if (tasks.isEmpty()) {
            log.debug("No compactable tool results to compact in LIGHT mode");
            return new ArrayList<>(messages);
        }
        Map<CompactionTask, String> summaries = runSummaries(tasks, messages);
        Map<Message, List<ContentBlock>> rebuilt = new IdentityHashMap<>();
        for (Map.Entry<CompactionTask, String> entry : summaries.entrySet()) {
            ToolResultTask task = (ToolResultTask)entry.getKey();
            List<ContentBlock> blocks = rebuilt.computeIfAbsent(task.message(), m -> new ArrayList<>(task.message().getContentBlocks()));
            int at = blocks.indexOf(task.result());
            blocks.set(at, new ToolResultBlock(task.result().toolUseId(), entry.getValue(), task.result().isError()));
        }
        List<Message> compacted = new ArrayList<>(messages.size());
        for (Message message : messages) {
            List<ContentBlock> blocks = rebuilt.get(message);
            compacted.add(blocks != null ? OutgoingMessage.copyWithBlocks((OutgoingMessage<?>)message, blocks) : message);
        }
        return compacted;
    }

    /**
     * MODERATE: every compactable exchange before the anchor becomes one summary, the assistant
     * turn with its tool calls and the turns that answered it together, with age-weighted detail.
     * The opening exchange, which has no assistant turn, keeps its non-compactable messages and
     * summarizes the rest. Non-compactable exchanges and the anchor stay as they are.
     */
    private List<Message> compactModerate(List<Message> messages, List<Exchange> exchanges) {
        List<CompactionTask> tasks = new ArrayList<>();
        for (Exchange exchange : exchanges.subList(0, exchanges.size() - 1)) {
            if (exchange.hasCompactableContent()) {
                tasks.add(new ExchangeTask(exchange));
            }
        }
        Map<CompactionTask, String> summaries = runSummaries(tasks, messages);
        Map<Exchange, String> byExchange = new IdentityHashMap<>();
        for (Map.Entry<CompactionTask, String> entry : summaries.entrySet()) {
            byExchange.put(((ExchangeTask)entry.getKey()).exchange(), entry.getValue());
        }
        List<Message> compacted = new ArrayList<>();
        for (Exchange exchange : exchanges) {
            String summary = byExchange.get(exchange);
            if (summary == null) {
                compacted.addAll(exchange.all());
                continue;
            }
            if (exchange.assistant == null) {
                // the opening: verbatim messages keep their place, the rest collapse into one summary
                boolean summarized = false;
                for (Message message : exchange.followers) {
                    if (message.isCompactable()) {
                        if (!summarized) {
                            compacted.add(summaryMessage(summary));
                            summarized = true;
                        }
                    }
                    else {
                        compacted.add(message);
                    }
                }
            }
            else {
                compacted.add(summaryMessage(summary));
            }
        }
        return compacted;
    }

    /**
     * AGGRESSIVE: every compactable exchange before the anchor collapses into one summary of
     * the whole history; non-compactable messages stay verbatim in order ahead of it. The anchor
     * exchange is summarized on its own, call and results together, when it may be compacted and
     * the turns that answer it carry tool results; otherwise it stays as it is.
     */
    private List<Message> compactAggressive(List<Message> messages, List<Exchange> exchanges) throws Exception {
        Exchange anchor = exchanges.getLast();
        List<Message> verbatim = new ArrayList<>();
        List<Message> toSummarize = new ArrayList<>();
        for (Exchange exchange : exchanges.subList(0, exchanges.size() - 1)) {
            if (exchange.assistant == null) {
                for (Message message : exchange.followers) {
                    (message.isCompactable() ? toSummarize : verbatim).add(message);
                }
            }
            else if (exchange.compactable()) {
                toSummarize.addAll(exchange.all());
            }
            else {
                verbatim.addAll(exchange.all());
            }
        }
        List<Message> compacted = new ArrayList<>(verbatim);
        if (!toSummarize.isEmpty()) {
            String summary = summarize(buildAggressiveSummaryPrompt(toSummarize), CompactionType.AGGRESSIVE_SUMMARY);
            OutgoingMessage<String> summaryMsg = new OutgoingMessage<>(StringResponseHandler.instance);
            summaryMsg.setTimestamp(Instant.now());
            summaryMsg.addText(String.format("""
                    === CONVERSATION SUMMARY ===
                    Summary of %d compacted messages:

                    %s
                    === END SUMMARY ===""", toSummarize.size(), summary));
            summaryMsg.setCache(true);
            compacted.add(summaryMsg);
        }
        if (anchor.compactable() && anchor.carriesToolResults()) {
            String recent = summarize(buildRecentExchangePrompt(anchor), CompactionType.RECENT_EXCHANGE);
            OutgoingMessage<String> recentMsg = new OutgoingMessage<>(StringResponseHandler.instance);
            recentMsg.setRole("user");
            recentMsg.setTimestamp(anchor.followers.getLast().getTimestamp());
            recentMsg.addText("[Recent exchange]: " + recent);
            recentMsg.setCache(true);
            compacted.add(recentMsg);
        }
        else {
            compacted.addAll(anchor.all());
        }
        return compacted;
    }

    private static OutgoingMessage<String> summaryMessage(String summary) {
        OutgoingMessage<String> msg = new OutgoingMessage<>(StringResponseHandler.instance);
        msg.setTimestamp(Instant.now());
        msg.addText(summary);
        msg.setCache(true);
        return msg;
    }

    /**
     * Cuts the history into exchanges: the outgoing messages before the first assistant turn
     * form the opening exchange; every assistant turn opens an exchange that runs to the next
     * assistant turn. A tool result always answers the immediately preceding assistant turn,
     * because the thinker appends one batched results message per assistant turn and the
     * framework's own notes (a legend, a tool announcement) are appended after it, so the
     * exchange is exactly the pairing unit. Package-private so tests can pin the cut.
     */
    static List<Exchange> exchanges(List<Message> messages) {
        List<Exchange> exchanges = new ArrayList<>();
        Exchange current = null;
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof IncomingMessage<?> incoming) {
                if (current != null) {
                    exchanges.add(current);
                }
                current = new Exchange(incoming, i);
            }
            else {
                if (current == null) {
                    current = new Exchange(null, i);
                }
                current.followers.add(message);
            }
        }
        if (current != null) {
            exchanges.add(current);
        }
        for (int i = 0; i < exchanges.size(); i++) {
            exchanges.get(i).ageWeight = (double)i / exchanges.size();
        }
        return exchanges;
    }

    /**
     * The pairing invariant every compacted list must satisfy: a tool call on an assistant turn
     * is answered in the message right after it, and a tool result on an outgoing turn answers a
     * call in the message right before it. An orphan on either side is a compactor bug, not a
     * model fault, and throws {@link IllegalStateException} naming the id.
     */
    static void requirePaired(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            Set<String> calls = toolUseIds(message);
            if (!calls.isEmpty()) {
                Set<String> answers = i + 1 < messages.size() ? toolResultIds(messages.get(i + 1)) : Set.of();
                for (String id : calls) {
                    if (!answers.contains(id)) {
                        throw new IllegalStateException("Compaction left tool_use " + id + " at message " + i + " without its tool_result in the next message");
                    }
                }
            }
            Set<String> results = toolResultIds(message);
            if (!results.isEmpty()) {
                Set<String> asked = i > 0 ? toolUseIds(messages.get(i - 1)) : Set.of();
                for (String id : results) {
                    if (!asked.contains(id)) {
                        throw new IllegalStateException("Compaction left tool_result " + id + " at message " + i + " without its tool_use in the previous message");
                    }
                }
            }
        }
    }

    private static Set<String> toolUseIds(Message message) {
        if (!(message instanceof IncomingMessage<?> incoming) || !incoming.hasContentBlocks()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (ContentBlock block : incoming.getContentBlocks()) {
            if (block instanceof ToolUseBlock use) {
                ids.add(use.toolUseId());
            }
        }
        return ids;
    }

    private static Set<String> toolResultIds(Message message) {
        if (!(message instanceof OutgoingMessage<?> outgoing)) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (ContentBlock block : outgoing.getContentBlocks()) {
            if (block instanceof ToolResultBlock result) {
                ids.add(result.toolUseId());
            }
        }
        return ids;
    }

    /**
     * Runs the summary jobs in parallel and collects what came back, by task. A task whose job
     * failed is absent from the result: best-effort by contract, its messages keep their
     * original form, the failure surfaces in the log with its cause, and the ladder decides
     * what is next. An interrupt is never best-effort. Package-private so tests can supply
     * canned summaries and exercise the selection and preservation logic without an LLM.
     */
    Map<CompactionTask, String> runSummaries(List<CompactionTask> tasks, List<Message> messages) {
        if (tasks.isEmpty()) {
            return Map.of();
        }
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        Map<CompactionTask, JobHandle<CompactionResult>> handles = new LinkedHashMap<>();
        for (CompactionTask task : tasks) {
            List<Message> contextWindow = extractContextWindow(messages, task.exchange().startIdx);
            CompactionJob job = new CompactionJob(this.parent, task, contextWindow, messages, compactionGrade);
            handles.put(task, dispatcher.submit(job));
        }
        log.debug("Submitted {} compaction jobs in parallel", handles.size());
        Map<CompactionTask, String> results = new LinkedHashMap<>();
        for (Map.Entry<CompactionTask, JobHandle<CompactionResult>> entry : handles.entrySet()) {
            try {
                results.put(entry.getKey(), requireSummary(entry.getValue().get()));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UncorrectableRuntimeLLMException("Interrupted while collecting compaction summaries", e);
            }
            catch (Exception e) {
                Exchange exchange = entry.getKey().exchange();
                log.warn("Compaction job failed for exchange at message {}, keeping the original messages", exchange.startIdx, e);
            }
        }
        return results;
    }

    /**
     * The messages around an exchange's start, for the summary model's bearings: the five before
     * its first message and the four after it, the exchange's own among them, which the job leaves
     * out when it renders the window. Package-private so the extent is testable.
     */
    static List<Message> extractContextWindow(List<Message> messages, int targetIdx) {
        int start = Math.max(0, targetIdx - 5);
        int end = Math.min(messages.size(), targetIdx + 5);
        return new ArrayList<>(messages.subList(start, end));
    }

    /**
     * Builds the prompt for the one summary of the whole compactable history, each message as the
     * model read it ({@link ModelText}), the longest clipped. Package-private so tests can pin the
     * prompt contract without an LLM.
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
            String content = ModelText.render(msg);
            if (content.length() > 500) {
                content = content.substring(0, 500) + "...";
            }
            prompt.append(content).append("\n\n");
        }
        prompt.repeat("=", 50).append("\n");
        prompt.append("Provide a clear, concise summary:");
        return prompt.toString();
    }

    /**
     * Builds the prompt for the anchor exchange at AGGRESSIVE: the tool calls the assistant made
     * and the results they returned, summarized together so the model keeps what it just learned.
     * Package-private so tests can pin the prompt contract without an LLM.
     */
    String buildRecentExchangePrompt(Exchange anchor) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Summarize this latest exchange concisely, preserving key findings.\n");
        prompt.append("Preserve any artifact references like «artifact:type~uuid» (e.g., «artifact:link:cite~a1b2c3»).\n\n");
        for (Message msg : anchor.all()) {
            prompt.append("[").append(msg.getRole()).append("]: ").append(ModelText.render(msg)).append("\n\n");
        }
        prompt.append("Provide a brief summary:");
        return prompt.toString();
    }

    /**
     * One summary through a {@link CompactionJob}, the same door the parallel path uses: resolved
     * through the picker, reserved, admitted and recorded like every other LLM call. A summary
     * that comes back empty is a failed compaction, not a summary.
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
     * An assistant turn and the outgoing turns that answer it, up to the next assistant turn.
     * The opening exchange has no assistant turn. Package-private so tests can build and
     * inspect exchanges.
     */
    static final class Exchange {
        final IncomingMessage<?> assistant;
        final List<Message> followers = new ArrayList<>();
        final int startIdx;
        double ageWeight;

        Exchange(IncomingMessage<?> assistant, int startIdx) {
            this.assistant = assistant;
            this.startIdx = startIdx;
        }

        /** Every message of the exchange in conversation order. */
        List<Message> all() {
            List<Message> all = new ArrayList<>(followers.size() + 1);
            if (assistant != null) {
                all.add(assistant);
            }
            all.addAll(followers);
            return all;
        }

        int size() {
            return followers.size() + (assistant != null ? 1 : 0);
        }

        /** Whether the exchange may be compacted as a unit: every message of it is compactable, so either side pins both. */
        boolean compactable() {
            if (assistant != null && !assistant.isCompactable()) {
                return false;
            }
            for (Message message : followers) {
                if (!message.isCompactable()) {
                    return false;
                }
            }
            return true;
        }

        /** Whether MODERATE has anything to summarize here: an opening with a compactable message, or a compactable exchange. */
        boolean hasCompactableContent() {
            if (assistant == null) {
                for (Message message : followers) {
                    if (message.isCompactable()) {
                        return true;
                    }
                }
                return false;
            }
            return compactable();
        }

        /** Whether an outgoing turn of the exchange carries a tool result. */
        boolean carriesToolResults() {
            for (Message message : followers) {
                if (!toolResultIds(message).isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        /** The name of the tool a result answers, from the call on this exchange's assistant turn; null when the call is not here. */
        String toolName(String toolUseId) {
            if (assistant == null || !assistant.hasContentBlocks()) {
                return null;
            }
            for (ContentBlock block : assistant.getContentBlocks()) {
                if (block instanceof ToolUseBlock use && use.toolUseId().equals(toolUseId)) {
                    return use.toolName();
                }
            }
            return null;
        }
    }

    /**
     * What one {@link CompactionJob} compacts: one tool result, one exchange, or a prompt the
     * compactor composed from the whole history.
     */
    sealed interface CompactionTask permits ToolResultTask, ExchangeTask, SummaryTask {
        CompactionType type();

        /** The exchange the task belongs to; a composed summary has none. */
        Exchange exchange();
    }

    /** One tool result of one outgoing message, compacted as {@link CompactionType#TOOL_RESULT}: the summary replaces its payload. */
    record ToolResultTask(Exchange exchange, OutgoingMessage<?> message, ToolResultBlock result) implements CompactionTask {
        @Override
        public CompactionType type() {
            return CompactionType.TOOL_RESULT;
        }
    }

    /** A whole exchange, compacted as {@link CompactionType#EXCHANGE}: one summary replaces every message of it. */
    record ExchangeTask(Exchange exchange) implements CompactionTask {
        @Override
        public CompactionType type() {
            return CompactionType.EXCHANGE;
        }
    }

    /** A prompt already composed, for {@link CompactionType#AGGRESSIVE_SUMMARY} and {@link CompactionType#RECENT_EXCHANGE}. */
    record SummaryTask(String prompt, CompactionType type) implements CompactionTask {
        @Override
        public Exchange exchange() {
            return null;
        }
    }

    /** Types of compaction. */
    enum CompactionType {
        /** LIGHT: one tool result's payload becomes a summary inside its block. */
        TOOL_RESULT,
        /** MODERATE: an assistant turn and the turns that answered it become one summary. */
        EXCHANGE,
        /** AGGRESSIVE: one narrative of every compactable message before the anchor. */
        AGGRESSIVE_SUMMARY,
        /** AGGRESSIVE: the anchor exchange, call and results together, on its own. */
        RECENT_EXCHANGE
    }
}
