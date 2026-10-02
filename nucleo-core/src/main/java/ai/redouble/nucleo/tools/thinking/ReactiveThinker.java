/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Thinker for message-driven interactions, executed as one finite TURN.
 *
 * <p>A turn is a burst of work over a durable conversation: it drains its inbox,
 * processing each message through potentially multiple LLM iterations with tool
 * execution, and completes the moment the inbox is empty at an idle boundary.
 * Messages sent while an exchange is running are injected at the next iteration
 * boundary so the model can be steered mid-task; a message arriving after the
 * turn has committed its close is refused ({@link #addMessage} returns false)
 * and the caller starts a fresh turn that resumes the conversation from
 * {@link ConversationService}. An exchange that fails FAILS the turn: the
 * conversation is rolled back and its marker saved, the failure event published,
 * and then the failure propagates - the job ends FAILED carrying the cause,
 * never a completed thinker over an exchange that produced nothing. The next
 * message starts a fresh turn over the saved conversation, so failing loudly
 * costs no continuity.
 *
 * <p><b>Note:</b> Reactive thinkers don't use structured input.
 * They process messages added via {@link #addMessage(String)}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-14)
 */
public abstract class ReactiveThinker extends AbstractThinker<VoidThinkerInput, VoidThinkerOutput> {
    private static final Logger log = LoggerFactory.getLogger(ReactiveThinker.class);
    private final Inbox inbox = new Inbox();
    // The rollback anchor: the most recent real user utterance appended to the
    // conversation. An abnormal exchange end truncates everything after it - the
    // failed exchange's partial turns must reach neither the store nor the next
    // LLM call. Object identity survives compaction: non-compactable messages are
    // carried over verbatim.
    private OutgoingMessage<String> lastUserUtterance;
    // per-MESSAGE tool budget (distinct from AbstractThinker's whole-run maxIterations);
    // exhaustion triggers one forced closing answer, never silence
    private int maxIterationsPerMessage = 25;
    /**
     * The effort this reactive seat's calls ask for. A reactive thinker has no input to carry
     * a depth (it processes messages, not a {@link ThinkerInput}), so the declaration lives
     * here. STANDARD is the same documented semantic default {@link ThinkerInput} carries,
     * stated once for the shape that has no input; a chat that wants another effort declares
     * it in its constructor through {@link #setDepth}.
     */
    private Depth depth = Depth.STANDARD;

    /**
     * Creates a reactive thinker.
     * This is the REQUIRED constructor for all tools.
     *
     * <p>Interactive by construction: a human is waiting on every answer. The declaration
     * is the subclass's, as for every thinker - a chat that wants the best model the
     * deployment serves declares {@link Grade#CEILING}, a reactive shape with a narrower
     * job states its own rung.
     *
     * @param parent the parent identity for lineage tracking
     */
    public ReactiveThinker(Identifiable parent, ThinkerDeclaration declaration) {
        this(parent, declaration, false);
    }

    /**
     * Chat-shaped thinker bound to tools that change nothing - a conversational surface that
     * can answer about a corpus but cannot act on it. See
     * {@link AbstractThinker#AbstractThinker(Identifiable, ThinkerDeclaration, boolean)} for what the binding
     * guarantees and how it travels to whatever this thinker delegates to.
     *
     * @param parent the parent identity for lineage tracking
     * @param forceReadOnly true to bind this thinker to tools that change nothing
     */
    protected ReactiveThinker(Identifiable parent, ThinkerDeclaration declaration, boolean forceReadOnly) {
        super(parent, declaration, forceReadOnly);
        // Interactive seat: a human is waiting. The old CHAT tier is this flag plus the grade.
        this.interactive = true;
        // Safety timeout: if thinker dies without cleanup, evict after 2 hours
        setRetainDuration(java.time.Duration.ofHours(2));
        // Normal completion: persist conversation 2 hours for session continuity
        setFinalRetainDuration(java.time.Duration.ofHours(2));
    }

    /**
     * The backstop half of the inbox invariant: a turn that dies before its
     * conversation is acquired never enters {@link #runThinkingLoop}, so its
     * finally cannot close the inbox. No holder exists on that path, so ordering
     * against the release is moot and the idempotent close here suffices.
     */
    @Override
    public VoidThinkerOutput execute(JobContext<VoidThinkerOutput> context) throws LLMReadableCheckedException {
        try {
            return super.execute(context);
        }
        finally {
            inbox.close();
        }
    }

    @Override
    protected void runThinkingLoop(ConversationContext conversation, JobContext<VoidThinkerOutput> context) throws LLMReadableCheckedException {
        try {
            // Declarations and skill admission are idempotent (add-only by name), so they run
            // unconditionally - fresh or restored, the conversation converges on the same state
            conversation.addTools(buildToolDefinitionBlocks());
            admitConfiguredSkills(conversation);
            initializeConversation(conversation, context);
            log.info("Starting turn for conversation {}", getConversationId());
            while (!context.isCancelled()) {
                String trigger = inbox.pollOrClose();
                if (trigger == null) {
                    break;
                }
                try {
                    // Track exchange boundaries for observability
                    int responsesBeforeExchange = context.getLlmResponses().size();
                    context.publish(new OrchestratorResumedEvent(context.getSnapshot().withState(JobState.RUNNING)));
                    processMessage(trigger, conversation, context);
                    ai.redouble.nucleo.harness.conversation.ConversationService.getInstance().saveConversation(conversation, this);
                    context.publish(new MessageCompleteEvent(context.getSnapshot(), true));

                    // Publish idle with LLM responses from this exchange only
                    List<LLMResponse<?>> allResponses = context.getLlmResponses();
                    List<LLMResponse<?>> exchangeResponses = new ArrayList<>(allResponses.subList(responsesBeforeExchange, allResponses.size()));
                    context.publish(new OrchestratorIdleEvent(context.getSnapshot().withState(JobState.IDLE), exchangeResponses, context.getAllMetadata()));
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("Turn interrupted");
                    saveAbnormalEnd(conversation, CANCELLED_MARKER);
                    break;
                }
                catch (CancellationException | JobContext.CancellationException e) {
                    log.info("Turn cancelled: {}", e.getMessage());
                    saveAbnormalEnd(conversation, markerFor(e, CANCELLED_MARKER));
                    break;
                }
                catch (Exception e) {
                    log.error("Error processing message in turn", e);
                    String errorMessage = e.getMessage();
                    if (errorMessage == null || errorMessage.isEmpty()) {
                        errorMessage = e.getClass().getSimpleName();
                    }
                    saveAbnormalEnd(conversation, markerFor(e, ERROR_MARKER));
                    context.publish(new MessageCompleteEvent(context.getSnapshot(), false, errorMessage));
                    // The turn ends as the failure it is: a thinker completing over a dead
                    // exchange would report success nothing produced - the user watches a
                    // silent chat while the job record says everything went fine
                    throw LLMReadableCheckedException.unwrap(e);
                }
            }
            log.info("Turn over for conversation {}, cancelled={}", getConversationId(), context.isCancelled());
        }
        finally {
            // The inbox never outlives the holder ownership it feeds: this close runs
            // before execute()'s finally releases the conversation, so a message racing
            // the shutdown is refused, and the new turn it triggers waits on the holder
            // until this one's release - accepted or refused, never lost.
            inbox.close();
        }
    }

    /**
     * Offers a message to the running turn.
     *
     * <p>Returns false when the turn has committed its close (or the message is
     * blank - callers pre-filter blanks, so to a well-behaved caller false means
     * exactly "this turn is over: resume the conversation with a new turn").
     *
     * @param message the message to add
     * @return true if the running turn will process the message
     */
    public boolean addMessage(String message) {
        if (message != null && !message.trim().isEmpty()) {
            return inbox.offer(message);
        }
        return false;
    }

    @Override
    protected VoidThinkerOutput getResult() {
        // Reactive thinkers have no final result
        return null;
    }

    // ================ Abstract Methods ================

    /**
     * Initializes the conversation at the start of the loop.
     *
     * @param conversation the conversation context
     * @param context      the job context
     */
    protected abstract void initializeConversation(ConversationContext conversation, JobContext<VoidThinkerOutput> context);

    /**
     * Processes a message through LLM iterations with tool support.
     *
     * @param message      the message to process
     * @param conversation the conversation context
     * @param context      the job context
     * @throws Exception if processing fails
     */
    protected void processMessage(String message, ConversationContext conversation, JobContext<VoidThinkerOutput> context) throws Exception {
        appendUserMessage(conversation, message);
        // the user-message boundary save: the first one of a minted conversation
        // CREATES its durable record, titled and embedded from this very message
        ai.redouble.nucleo.harness.conversation.ConversationService.getInstance().saveConversation(conversation, this);

        JobDispatcher dispatcher = JobDispatcher.getInstance();
        String userId = context.getUserId();

        int iteration = 0;
        // an adaptive-thinking turn can legitimately end with reasoning only - the
        // model "finished" without delivering the answer text; one nudge per
        // exchange asks for the answer before that counts as a failure
        boolean nudged = false;
        // one slot past the budget is reserved for the forced closing answer
        while (iteration++ < maxIterationsPerMessage + 1) {
            boolean closing = iteration > maxIterationsPerMessage;
            if (closing) {
                // budget exhausted mid-investigation: the user is waiting and silence
                // is the one unacceptable outcome, so the last call must ANSWER from
                // whatever evidence the exchange has already gathered
                log.warn("Iteration budget ({}) exhausted; forcing a final answer", maxIterationsPerMessage);
                context.publishUserNotification(UserNotificationEvent.Severity.WARNING, "Investigation Budget Exhausted",
                        "Answering from the evidence gathered so far");
                OutgoingMessage<String> closingInstruction = new OutgoingMessage<>(StringResponseHandler.instance);
                closingInstruction.setRole("user");
                closingInstruction.setTimestamp(java.time.Instant.now());
                closingInstruction.addText(CLOSING_INSTRUCTION);
                conversation.getMessages().add(closingInstruction);
            }
            else {
                // Steering: follow-ups sent while this exchange runs are appended at the
                // iteration boundary, so the model sees them and adjusts course mid-task.
                // The closing call is exempt - it was instructed to answer NOW, so queued
                // messages stay in the inbox and open the next exchange instead.
                List<String> injected = inbox.drainForInjection();
                if (injected != null) {
                    for (String followUp : injected) {
                        appendUserMessage(conversation, followUp);
                    }
                    ai.redouble.nucleo.harness.conversation.ConversationService.getInstance().saveConversation(conversation, this);
                    log.info("Injected {} follow-up message(s) into the running exchange", injected.size());
                }
            }
            // Compact conversation if needed before this iteration
            conversation = performCompactionLoop(conversation, context, iteration);

            // Use ThinkingResponseHandler which handles tool input conversion
            @SuppressWarnings("unchecked")
            ThinkingResponseHandler<String> responseHandler =
                    new ThinkingResponseHandler<>(toolRegistry, new PojoResponseHandler<ThinkingResponse<String>>((Class)ThinkingResponse.class), StringResponseHandler.instance);

            // Create LLM request message
            OutgoingMessage<ThinkingResponse<String>> llmMessage = new OutgoingMessage<>(responseHandler);
            llmMessage.setRole("user");
            llmMessage.setTimestamp(java.time.Instant.now());
            if (cacheAllMessages) {
                llmMessage.setCache(true);
            }
            conversation.getMessages().add(llmMessage);

            // Submit LLM call with token overflow handling
            LLMCall<ThinkingResponse<String>> llmCall = newLLMCall(context, conversation);
            JobHandle<ThinkingResponse<String>> handle = submitInIteration((long) iteration, llmCall);
            ThinkingResponse<String> response;
            try {
                response = handle.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof TokenEstimateExceedsLimitException overflow) {
                    final long attempt = iteration;
                    CompactedReply<ThinkingResponse<String>> reply = compactAndRetry(overflow, conversation, context,
                            compacted -> submitInIteration(attempt, this.<ThinkingResponse<String>>newLLMCall(context, compacted)).get());
                    conversation = reply.conversation();
                    response = reply.response();
                } else if (correctableFailureAppended(e, conversation, iteration)) {
                    // A reply the model can fix is a correction turn here exactly as it is for
                    // every other agentic loop: the exchange continues instead of ending on an
                    // internal error the model was never told about.
                    continue;
                } else {
                    throw e;
                }
            }

            if (response == null) {
                log.error("LLM call failed");
                context.publishUserNotification(UserNotificationEvent.Severity.ERROR, "Message Processing Failed", "Failed to process your message due to LLM error");
                throw new ExternalServiceException("LLM", "Failed to respond to message");
            }

            publishReasoning(response, context);

            if (response.isFinalAnswer() && response.getAnswer() != null) {
                OutgoingMessage<String> assistantMessage = new OutgoingMessage<>(StringResponseHandler.instance);
                assistantMessage.setRole("assistant");
                assistantMessage.setTimestamp(java.time.Instant.now());
                assistantMessage.addText(response.getAnswer());
                assistantMessage.setCompactable(false);  // Preserve final answer verbatim during compaction
                conversation.getMessages().add(assistantMessage);
                Map<String, Artifact> artifacts = conversation.getArtifactRegistry().getAllArtifacts();
                streamResponse(response.getAnswer(), artifacts, context);
                return;
            }

            if (closing) {
                // the closing call was instructed to answer and produced tool calls
                // anyway; executing them would start an investigation there is no
                // budget to finish, so this exchange ends in the failure path below
                break;
            }
            List<ToolCall> toolCalls = response.getToolCalls();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                executeTools(toolCalls, conversation, dispatcher, userId, context, (long) iteration);
            }
            else if (!nudged) {
                nudged = true;
                log.warn("LLM turn ended with neither answer nor tool calls; nudging for the final answer");
                OutgoingMessage<String> nudge = new OutgoingMessage<>(StringResponseHandler.instance);
                nudge.setRole("user");
                nudge.setTimestamp(java.time.Instant.now());
                nudge.addText(NUDGE_INSTRUCTION);
                conversation.getMessages().add(nudge);
            }
            else {
                log.warn("LLM provided neither response nor tool calls, even after the nudge");
                context.publishUserNotification(UserNotificationEvent.Severity.WARNING, "No Response Generated", "LLM did not generate a response to your message");
                throw new SystemException("AgentLoop", "No tools and no response generated", null);
            }

            context.checkCancellation();
        }

        // reachable only when even the forced closing call did not answer
        log.warn("No final answer even after the forced closing call");
        context.publishUserNotification(UserNotificationEvent.Severity.ERROR, "No Answer Produced",
                "The investigation ran out of budget and the closing call still produced no answer. Please re-ask, more narrowly.");
    }

    /**
     * Appended when the per-message iteration budget runs out: the exchange's last
     * call must answer the user from the evidence already gathered - a truncated
     * investigation with a stated answer beats a silent one.
     */
    private static final String CLOSING_INSTRUCTION = """
            INVESTIGATION BUDGET EXHAUSTED. Do not call any more tools. Answer the user's question NOW, \
            using only the evidence already gathered in this exchange. Be explicit about which parts of \
            the question remain unverified because the budget ran out, and what you would have checked next.""";

    private static final String NUDGE_INSTRUCTION = """
            Your previous turn ended without an answer and without tool calls - whatever you were \
            composing was not delivered. State your final answer to the user NOW.""";

    /**
     * The tool-call budget for answering ONE user message. When it runs out, one
     * additional closing call is made that must answer from the gathered evidence.
     */
    public void setMaxIterationsPerMessage(int maxIterationsPerMessage) {
        this.maxIterationsPerMessage = maxIterationsPerMessage;
    }

    /** Declares this seat's effort; see the field. */
    public void setDepth(Depth depth) {
        if (depth == null) {
            throw new IllegalArgumentException("A reactive thinker's depth cannot be unset");
        }
        this.depth = depth;
    }

    @Override
    public Depth getDepth() {
        return depth;
    }

    /**
     * Publishes LLM reasoning to the user if available.
     */
    private void publishReasoning(ThinkingResponse<String> response, JobContext<VoidThinkerOutput> context) {
        if (response.getReasoning() != null) {
            String reasoning = response.getReasoning().getUserFriendlyDescription();
            if (reasoning != null && !reasoning.isEmpty() && !reasoning.equals("No reasoning provided")) {
                context.publishUserNotification(UserNotificationEvent.Severity.INFO, "Thinking", reasoning);
            }
        }
    }

    /**
     * Streams a response to the user.
     * Default implementation logs response. Subclasses can override to publish events.
     *
     * @param response  the response text to stream
     * @param artifacts artifacts collected during conversation
     * @param context   the job context
     */
    protected void streamResponse(String response, Map<String, Artifact> artifacts, JobContext<VoidThinkerOutput> context) {
        log.info("Response: {}", (response.length() > 100 ? response.substring(0, 100) + "..." : response));
    }

    /**
     * Appends one user utterance to the conversation, verbatim and non-compactable -
     * the shared shape for exchange triggers and mid-exchange injections alike - and
     * records it as the rollback anchor for an abnormal exchange end.
     * Package-private for same-package tests of the rollback.
     */
    void appendUserMessage(ConversationContext conversation, String text) {
        OutgoingMessage<String> userMessage = new OutgoingMessage<>(StringResponseHandler.instance);
        userMessage.setRole("user");
        userMessage.setTimestamp(java.time.Instant.now());
        userMessage.addText(text);
        userMessage.setCompactable(false);  // Preserve user input verbatim during compaction
        conversation.getMessages().add(userMessage);
        lastUserUtterance = userMessage;
    }

    /**
     * The abnormal-end save: rolls the conversation back to the last user utterance,
     * records the marker as the exchange's outcome, and persists. The failed
     * exchange's partial turns - possibly an assistant tool_use with no results yet -
     * must reach neither the store nor the next LLM call: a history carrying an
     * unpaired tool_use is rejected wholesale by the provider.
     * Package-private for same-package tests.
     */
    void saveAbnormalEnd(ConversationContext conversation, String marker) {
        List<Message> messages = conversation.getMessages();
        int anchor = lastUserUtterance != null ? messages.lastIndexOf(lastUserUtterance) : -1;
        if (anchor < 0) {
            // every trigger and injection records the anchor and it survives
            // compaction by identity; no anchor means no exchange ever started -
            // nothing was mutated since the last boundary, nothing to roll back
            log.warn("No user utterance anchor for the abnormal end of {} - nothing to roll back", getConversationId());
            return;
        }
        messages.subList(anchor + 1, messages.size()).clear();
        OutgoingMessage<String> outcome = new OutgoingMessage<>(StringResponseHandler.instance);
        outcome.setRole("assistant");
        outcome.setTimestamp(java.time.Instant.now());
        outcome.addText(marker);
        messages.add(outcome);
        ai.redouble.nucleo.harness.conversation.ConversationService.getInstance().saveConversation(conversation, this);
    }

    /**
     * The marker an abnormal end leaves in the conversation: the LLM-readable
     * message when the failure carries one - on resume the model should know WHY
     * the previous attempt produced no answer - and the given fallback otherwise.
     * Package-private for same-package tests.
     */
    static String markerFor(Throwable failure, String fallback) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof LLMReadable readable) {
                return INCOMPLETE_PREFIX + readable.getLLMMessage();
            }
        }
        return fallback;
    }

    /**
     * Abnormal-end markers. They become part of the conversation, so a resumed model
     * sees that the previous attempt ended without an answer instead of a user
     * message hanging unanswered.
     */
    private static final String INCOMPLETE_PREFIX = "I could not complete a response to the previous message. ";
    private static final String ERROR_MARKER = "I could not complete a response to the previous message due to an internal error.";
    private static final String CANCELLED_MARKER = "I could not complete a response to the previous message: the operation was cancelled.";

    /**
     * The turn's inbox: a tiny state machine guarded by its own monitor. A message is
     * either accepted (this turn will process it) or refused (the turn has closed and
     * the caller starts a new one) - never silently lost. The close commits atomically
     * against concurrent offers and is permanent. Package-private for same-package
     * tests of the handshake.
     */
    static final class Inbox {
        private final ArrayDeque<String> messages = new ArrayDeque<>();
        private boolean closed;

        /** Accepts the message while the turn is open; false once closed. */
        synchronized boolean offer(String message) {
            if (closed) {
                return false;
            }
            messages.addLast(message);
            return true;
        }

        /**
         * The next message, or null after committing the close: an empty inbox at an
         * idle boundary IS the end of the turn.
         */
        synchronized String pollOrClose() {
            String message = messages.pollFirst();
            if (message == null) {
                closed = true;
            }
            return message;
        }

        /** Drains queued messages mid-exchange without closing, or null when empty. */
        synchronized List<String> drainForInjection() {
            if (messages.isEmpty()) {
                return null;
            }
            List<String> drained = new ArrayList<>(messages);
            messages.clear();
            return drained;
        }

        /** Idempotent unconditional close - the backstop for turns that die early. */
        synchronized void close() {
            closed = true;
        }
    }
}