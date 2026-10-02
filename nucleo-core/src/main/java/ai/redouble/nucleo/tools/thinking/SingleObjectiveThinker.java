/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.tools.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Goal-driven thinker working toward a single final answer.
 *
 * <p>Iteratively calls the LLM to either execute tools or provide
 * a final answer, continuing until completion or max iterations.
 *
 * <p><b>Input:</b> Subclasses access their structured input via {@code this.input}
 * which is set before {@code execute()} is called.
 *
 * @param <I> the type of the input (must extend ThinkerInput)
 * @param <O> the type of the final answer (must extend ThinkerOutput)
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-14)
 */
public abstract class SingleObjectiveThinker<I extends ThinkerInput, O extends ThinkerOutput<? extends Reasoning>> extends AbstractThinker<I, O> {
    private static final Logger log = LoggerFactory.getLogger(SingleObjectiveThinker.class);
    /** Default thinking-iteration ceiling; the per-instance knob is {@link #setMaxIterations}. */
    public static final int DEFAULT_MAX_ITERATIONS = 50;
    protected ResponseHandler<O> answerHandler;
    protected O finalAnswer;
    protected int maxIterations = DEFAULT_MAX_ITERATIONS;

    /**
     * Creates a single-objective thinker.
     * This is the REQUIRED constructor for all tools.
     *
     * <p>Subclasses implement {@link #declareDefaultTools()} to specify available tools
     * and pass the {@link ThinkerDeclaration} naming the capability floor their work
     * needs and the answer size it books - there is no default for either.
     *
     * @param parent      the parent identity for lineage tracking
     * @param declaration what this seat declares: its grade and answer size, required
     */
    public SingleObjectiveThinker(Identifiable parent, ThinkerDeclaration declaration) {
        this(parent, declaration, false);
    }

    /**
     * Goal-directed thinker bound to tools that change nothing. See
     * {@link AbstractThinker#AbstractThinker(Identifiable, ThinkerDeclaration, boolean)} for what the binding
     * guarantees and how it travels to whatever this thinker delegates to.
     *
     * @param parent the parent identity for lineage tracking
     * @param forceReadOnly true to bind this thinker to tools that change nothing
     */
    protected SingleObjectiveThinker(Identifiable parent, ThinkerDeclaration declaration, boolean forceReadOnly) {
        super(parent, declaration, forceReadOnly);
        this.cacheAllMessages = true;
        // Safety timeout: if thinker dies without cleanup, evict after 30 min
        setRetainDuration(java.time.Duration.ofMinutes(30));
        // Normal completion: evict immediately
        setFinalRetainDuration(java.time.Duration.ZERO);
    }

    /**
     * A goal-directed thinker's effort is its input's: the caller (a parent thinker or
     * code) sets the depth on the {@link ThinkerInput}, and the parent's clamp keeps a
     * child from exceeding the parent's. {@link ThinkerInput} initializes it to STANDARD,
     * the one documented semantic default.
     */
    @Override
    public Depth getDepth() {
        return getInput().getDepth();
    }

    @Override
    protected void runThinkingLoop(ConversationContext conversation, JobContext<O> context) throws LLMReadableCheckedException {
        try {
        runThinkingLoopInternal(conversation, context);
        } catch (LLMReadableCheckedException e) {
            throw e;
        } catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }

    private void runThinkingLoopInternal(ConversationContext conversation, JobContext<O> context) throws Exception {
        // A thinker instance can be submitted more than once (a doer re-running a challenged
        // step through the same instance). Without this, a run that ends without a final answer
        // leaves the previous run's answer in place and getResult() hands it back as if it were
        // this run's, which is indistinguishable from success.
        finalAnswer = null;
        Prompt systemPrompt = getSystemPrompt();
        List<ToolDefinitionBlock> toolBlocks = buildToolDefinitionBlocks();

        // Check if this is a resumed conversation (conversationId was explicitly set AND conversation has messages)
        boolean isResuming = conversationId != null && !conversation.getMessages().isEmpty();

        if (isResuming) {
            // Resuming an existing conversation - add new input's query as user message
            String resumeQuery = input != null ? input.getQuery() : null;
            if (resumeQuery != null && !resumeQuery.isEmpty()) {
                OutgoingMessage<String> challengeMessage = new OutgoingMessage<>(StringResponseHandler.instance);
                challengeMessage.setRole("user");
                challengeMessage.addText(resumeQuery);
                conversation.getMessages().add(challengeMessage);
                log.info("Resuming conversation with message: {}...", resumeQuery.substring(0, Math.min(100, resumeQuery.length())));
            }
        }
        else {
            // New conversation - put the task if no composition has run yet. The legend and
            // tool guidance are framework slots the conversation manages itself; the task is
            // the one authored slot, and a restored conversation keeps the one it was born with.
            if (!conversation.hasMainObjective(TASK_OBJECTIVE_KEY)) {
                ThinkerObjective objective = new ThinkerObjective();
                objective.setPrompt(systemPrompt);
                objective.setInput(input);
                conversation.putMainObjective(TASK_OBJECTIVE_KEY, objective);
                context.putMetadata("prompt_contexts", List.of(systemPrompt.context()));
                conversation.addTools(toolBlocks);
                admitConfiguredSkills(conversation);
                String followup = getSeededFollowupMessage();
                if (followup != null && !followup.isBlank()) {
                    OutgoingMessage<String> seeded = new OutgoingMessage<>(StringResponseHandler.instance);
                    seeded.setRole("user");
                    seeded.setTimestamp(java.time.Instant.now());
                    if (cacheAllMessages) {
                        seeded.setCache(true);
                    }
                    seeded.addText(followup);
                    conversation.getMessages().add(seeded);
                }
            }
        }

        log.info("Thinker {}", (isResuming ? "resuming" : "starting with: " + systemPrompt.key()));
        context.publishUserProgress("Analysis Started", "Initializing conversation with LLM", 5);

        // Get dispatcher for submitting jobs
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        String userId = context.getUserId();

        int iteration = 0;

        // Main conversation loop
        while (iteration++ < maxIterations) {
            // Compact conversation if needed before this iteration
            conversation = performCompactionLoop(conversation, context, iteration);

            int progressPercent = 10 + (iteration * 80 / maxIterations);
            context.publishUserProgress("Processing", "Thinking iteration " + iteration + " of " + maxIterations, progressPercent);

            // Use ThinkingResponseHandler which handles tool input conversion
            @SuppressWarnings("unchecked")
            ThinkingResponseHandler<O> responseHandler =
                    new ThinkingResponseHandler<>(toolRegistry, new PojoResponseHandler<ThinkingResponse<O>>((Class)ThinkingResponse.class), answerHandler);

            // Ask for response using the thinking response handler
            OutgoingMessage<ThinkingResponse<O>> message = new OutgoingMessage<>(responseHandler);
            message.setRole("user");
            message.setTimestamp(java.time.Instant.now());
            if (cacheAllMessages) {
                message.setCache(true);
            }
            conversation.getMessages().add(message);

            // Submit LLM call with token overflow handling
            ThinkingResponse<O> response;
            try {
                response = submitThinkingCall(conversation, context, iteration);
            }
            catch (ExecutionException e) {
                if (e.getCause() instanceof TokenEstimateExceedsLimitException overflow) {
                    final int attempt = iteration;
                    CompactedReply<ThinkingResponse<O>> reply = compactAndRetry(overflow, conversation, context,
                            compacted -> submitThinkingCall(compacted, context, attempt));
                    conversation = reply.conversation();
                    response = reply.response();
                }
                else if (correctableFailureAppended(e, conversation, iteration)) {
                    continue;
                }
                else {
                    throw e;
                }
            }

            if (response == null) {
                log.error("LLM call failed - aborting");
                context.publishUserNotification(UserNotificationEvent.Severity.ERROR, "Analysis Failed", "LLM failed to respond. Analysis incomplete.");
                throw new ExternalServiceException("LLM", "Failed to respond - analysis incomplete");
            }

            logReasoning(response, iteration, context);

            if (response.isFinalAnswer()) {
                context.publishUserProgress("Completed", "Analysis complete - preparing final answer", 95);

                finalAnswer = response.getAnswer();
                if (finalAnswer != null) {
                    // Resolve artifact refs to actual objects and populate the artifacts field
                    resolveArtifacts(finalAnswer, conversation);
                    if (!acceptAnswer(finalAnswer, conversation, context)) {
                        finalAnswer = null;
                        continue;
                    }
                    streamAnswer(finalAnswer, context);
                }
                return;
            }

            // Execute requested tools
            List<ToolCall> toolCalls = response.getToolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                log.warn("LLM returned no tool calls and final_answer=false at iteration {} - re-prompting", iteration);
                OutgoingMessage<String> correction = new OutgoingMessage<>(StringResponseHandler.instance);
                correction.addText("No tool_calls and final_answer=false. Call a tool or set final_answer=true.");
                conversation.getMessages().add(correction);
                continue;
            }

            // Stream tool calls info using human-readable display names
            if (!response.getToolCalls().isEmpty()) {
                for (ToolCall call : response.getToolCalls()) {
                    String displayName = toolRegistry.getDisplayName(call.getToolName());
                    String action = toolRegistry.getActionVerb(call.getToolName());
                    String title = !action.isEmpty() ? action : displayName;
                    context.publishUserNotification(UserNotificationEvent.Severity.INFO, title, displayName);
                }
            }

            executeTools(toolCalls, conversation, dispatcher, userId, context, (long) iteration);

            // Check for cancellation before next iteration
            context.checkCancellation();
        }

        // Reached max iterations without final answer
        log.warn("Thinker reached max iterations without final answer");
        context.publishUserNotification(UserNotificationEvent.Severity.WARNING, "Max Iterations Reached",
                "Analysis stopped after " + maxIterations + " iterations without reaching a conclusion");
    }

    /**
     * One LLM turn: submits the conversation as an {@link LLMCall} under the given
     * iteration and waits for the parsed response. The seam between the loop and the
     * model; the loop's overflow and correction handling wraps this call.
     */
    protected ThinkingResponse<O> submitThinkingCall(ConversationContext conversation, JobContext<O> context, int iteration) throws ExecutionException, InterruptedException {
        LLMCall<ThinkingResponse<O>> llmCall = newLLMCall(context, conversation);
        return submitInIteration((long) iteration, llmCall).get();
    }

    /**
     * Runs the declared validation guards against a candidate final answer while this thinker
     * still holds its conversation. A refusal becomes a correction turn and the candidate is
     * dropped, so the model answers again with everything it knows; the iteration budget bounds
     * the retries. Anything else a guard fails with propagates as itself and fails this thinker
     * closed. It stays here rather than beside the shared correction policy because the guards
     * are declared against this seat's typed answer, and a reactive seat's answer is prose it
     * streams rather than the output type it declares.
     */
    private boolean acceptAnswer(O candidate, ConversationContext conversation, JobContext<O> context) throws Exception {
        try {
            GuardrailEnforcer.enforceValidation(declareValidationGuardrails(), candidate, context);
            return true;
        }
        catch (GuardrailException refusal) {
            log.warn("Final answer refused by validation guardrail: {}", refusal.getLLMMessage());
            context.publishUserNotification(UserNotificationEvent.Severity.WARNING, "Answer Refused", refusal.getLLMMessage());
            appendCorrection(conversation, refusal);
            return false;
        }
    }

    /**
     * Publishes the final answer to the user as one done chunk: the answer's text when it is
     * a string, else the answer as JSON, so a watcher of the stream reads the typed answer's
     * fields and never a class name. Only streams if this is a top-level thinker - thinkers
     * invoked as tools return their results to the parent thinker instead of streaming
     * directly to the user.
     */
    protected void streamAnswer(O answer, JobContext<O> context) {
        // If this thinker is being invoked as a tool, don't stream to user
        // The answer will be returned to the parent thinker as a tool result
        if (isInvokedAsTool()) {
            log.debug("Thinker invoked as tool - skipping direct user streaming");
            return;
        }

        String answerText = answer instanceof CharSequence text ? text.toString() : NucleoJsonSerializer.write(answer);
        if (answerText == null || answerText.isEmpty()) {
            return;
        }

        // Send the complete answer as a single "done" chunk
        StreamChunk finalChunk = StreamChunk.done(answerText);
        ContentStreamEvent streamEvent = new ContentStreamEvent(context.getSnapshot(), finalChunk);
        context.publish(streamEvent);
    }

    /**
     * Logs and streams the LLM's reasoning if available.
     */
    protected void logReasoning(ThinkingResponse<O> response, int iteration, JobContext<O> context) {
        if (response.getReasoning() != null) {
            String reasoningText = response.getReasoning().getUserFriendlyDescription();
            log.debug("Iteration {} reasoning: {}", iteration, reasoningText);

            // Publish the reasoning (clean format, no iteration prefix)
            context.publishUserNotification(UserNotificationEvent.Severity.INFO, "Reasoning", reasoningText);
        }
    }

    /**
     * Resolves artifact references to actual objects from the conversation's registry
     * and populates the artifacts field of the ThinkerOutput.
     */
    protected void resolveArtifacts(O finalAnswer, ConversationContext conversation) {
        if (finalAnswer == null) {
            return;
        }
        // ThinkerOutput has both artifact_refs (from ArtifactResponse) and artifacts field
        Set<String> refsToResolve = new HashSet<>();
        // Declared refs are canonical after the parse gate; normalized again here because
        // an output can also be built programmatically, off the thinking path
        if (finalAnswer.hasArtifactRefs()) {
            for (String ref : finalAnswer.getArtifactRefs()) {
                refsToResolve.add(ArtifactRegistry.normalizeToKey(ref));
            }
        }
        // Refs mentioned in the text ride along, through the one authority for the wire shape
        refsToResolve.addAll(ArtifactRegistry.refsMentionedIn(finalAnswer.toString()));
        // Resolve refs to actual artifacts from the registry
        ArtifactRegistry registry = conversation.getArtifactRegistry();
        for (String ref : refsToResolve) {
            Artifact artifact = registry.get(ref);
            if (artifact != null) {
                finalAnswer.addArtifact(artifact);
                log.debug("Resolved artifact: {}", ref);
            }
            else {
                log.warn("Could not resolve artifact reference: {}", ref);
            }
        }
        if (!finalAnswer.getArtifacts().isEmpty()) {
            log.info("Resolved {} artifacts for propagation to parent", finalAnswer.getArtifacts().size());
        }
    }

    @Override
    protected O getResult() {
        return finalAnswer;
    }

    // ================ Abstract Methods ================

    /**
     * First-class Prompt carrying this thinker's static system instructions.
     *
     * <p>Most subclasses do NOT override this method. They override
     * {@link #getSystemPromptText()} instead, returning the literal prompt text annotated
     * with {@code @StaticPrompt}; the default implementation here lazy-binds that text to
     * the registry under an auto-key derived from the subclass FQN, so {@link Prompts#produce}
     * - and the substitution layers above it - apply.
     *
     * <p>Override this method directly only when the prompt cannot be expressed as a
     * literal: composition with runtime data, multi-source assembly, etc. Annotate the
     * override with {@code @DynamicPrompt} to declare the per-call contract.
     */
    protected Prompt getSystemPrompt() throws LLMReadableCheckedException {
        String text = getSystemPromptText();
        if (text == null) {
            throw new UnsupportedOperationException(
                "Subclass " + getClass().getName() +
                " must override getSystemPromptText() (preferred for static prompts) " +
                "or getSystemPrompt() (for dynamic composition)");
        }
        return Prompts.bindStaticDefault(getClass().getName(), text);
    }

    /**
     * Simple-case override: return the static system-prompt text. Annotate the override
     * with {@code @StaticPrompt} to declare the immutability contract.
     *
     * <p>The auto-derived registry key is the subclass FQN. Pin an explicit key on the
     * annotation ({@code @StaticPrompt("ops.visible.key")}) when ops needs a stable name
     * for DB-stored overrides that survives class renames.
     */
    protected String getSystemPromptText() {
        return null;
    }

    /**
     * Optional user message seeded once, immediately after the objective and before the thinking
     * loop begins. Returns null by default (no extra seeded message). Override to deliver a final
     * piece of context that should sit at the end of the seeded conversation - e.g. a plan the agent
     * must follow - so it carries recency the objective block, which leads with stable bulk context,
     * cannot. Only seeded for a fresh conversation, not when resuming.
     *
     * @return the follow-up user message text, or null for none
     */
    protected String getSeededFollowupMessage() {
        return null;
    }

    // ================ Getters/Setters ================

    public ResponseHandler<O> getAnswerHandler() {
        return answerHandler;
    }

    public void setAnswerHandler(final ResponseHandler<O> answerHandler) {
        this.answerHandler = answerHandler;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }
}