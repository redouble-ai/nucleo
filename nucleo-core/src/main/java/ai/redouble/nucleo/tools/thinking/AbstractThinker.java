/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.artifacts.tools.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.compaction.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.builtin.*;
import ai.redouble.nucleo.tools.registry.*;
import ai.redouble.nucleo.util.*;
import org.slf4j.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Base class for implementing reasoning agents that coordinate tool execution.
 *
 * <p><b>Architecture Overview:</b>
 * Thinkers are specialized tools that hold NO resources. They operate in a simple loop:
 * <ol>
 *   <li>Ask LLM what to do next (via LLMCall job)</li>
 *   <li>Execute requested tools (as separate jobs)</li>
 *   <li>Add results to conversation</li>
 *   <li>Repeat until LLM provides final answer</li>
 * </ol>
 *
 * <p><b>Resource Management:</b>
 * Since thinkers can run for hours or days, they cannot hold LLM clients or database
 * connections. Instead, each LLM call and tool execution is submitted as a separate
 * job through the JobDispatcher, which allocates and releases resources as needed.
 *
 * <p><b>Tool Composition:</b>
 * Since thinkers extend Tool, they can be registered in ToolRegistry and invoked by
 * other thinkers. This enables hierarchical multi-agent systems where meta-agents
 * coordinate specialist agents.
 *
 * <p><b>Data Flow:</b>
 * <pre>
 * LLM → JSON → ThinkingResponse (with ToolCalls)
 *   ↓
 * ToolCall.input (Object) → converted to → Tool's input POJO
 *   ↓
 * Tool executes → returns output POJO
 *   ↓
 * Output POJO → added to conversation → back to LLM
 * </pre>
 *
 * @param <I> Input type - must extend ThinkerInput for uniform query handling
 * @param <O> Output type - must extend ThinkerOutput for uniform response handling
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-19)
 */
public abstract class AbstractThinker<I extends ThinkerInput, O extends ThinkerOutput<? extends Reasoning>> extends AbstractOrchestrator<I, O>
        implements Thinker<I, O>, ai.redouble.nucleo.harness.artifacts.tools.ArtifactRegistryAware {
    private static final Logger log = LoggerFactory.getLogger(AbstractThinker.class);

    /**
     * Metadata key for serialized artifacts in the thinker's registry at completion.
     */
    public static final String OBS_ARTIFACTS_IN = "obs.artifacts_in";

    /**
     * Metadata key for serialized artifacts propagated to parent by the thinker.
     */
    public static final String OBS_ARTIFACTS_OUT = "obs.artifacts_out";

    /**
     * Objective-map key under which a thinker puts its task - the authored objective
     * (prompt + input). One slot per conversation: composing again re-puts the same key,
     * so a resumed conversation keeps the objective it was born with.
     */
    protected static final String TASK_OBJECTIVE_KEY = "task";

    /**
     * Synthetic tool_result text the reconciliation net emits when a tool_use was left
     * unanswered by the execution loop. Anthropic requires every tool_use in an assistant
     * turn to be paired with a tool_result in the next user turn; this backfill keeps that
     * invariant structurally true even if a future code path fails to emit a result.
     */
    private static final String RECONCILE_TEXT =
            "Tool result was not produced due to an internal error; you may retry or choose a different tool.";

    /**
     * The capability floor this thinker's calls need, or {@link Grade#CEILING} for a seat
     * that wants the strongest model the deployment serves. No default, silent or otherwise:
     * it arrives in the {@link ThinkerDeclaration} the constructor requires, chosen for the
     * work the thinker does, and a caller may raise it afterwards for a particular run.
     */
    protected Grade grade;
    /**
     * How much answer this thinker's turns book: an {@link OutputSize} rung sized to the
     * largest turn (a loop turn may be a tool call or the final answer), or a raw count for
     * the seat that fits no rung. No default, silent or otherwise: it arrives in the
     * {@link ThinkerDeclaration} next to the grade.
     */
    protected OutputDeclaration outputDeclaration;
    /**
     * The thinker's own comfort context window, or null to let the catalog entry and the
     * framework default decide. See {@link ContextWindowManager}.
     */
    protected Integer comfortContextTokens;
    /** The thinker's own compaction trigger in (0, 1], or null for the framework default. */
    protected Double compactionTrigger;
    /** Whether a human is waiting on this thinker's answers - a demand flavor pickers may weigh. */
    protected boolean interactive;
    protected final ToolRegistry toolRegistry;
    protected boolean cacheAllMessages = false;

    /**
     * This thinker's OWN binding, fixed at construction so nothing can unset it - not
     * even trusted code rewriting the scope guard before dispatch. An inherited binding
     * arrives separately, through the guard. See
     * {@link #AbstractThinker(Identifiable, ThinkerDeclaration, boolean)}.
     */
    private final boolean forceReadOnly;

    /**
     * Seed registry conveyed before execution (e.g. a fan-out doer seeding a
     * thinker worker with its pair). The thinker's live registry is owned by its
     * conversation, which does not exist until execution - so the seed buffers
     * here and merges into the conversation registry when it materializes.
     */
    private ArtifactRegistry seedArtifactRegistry;
    /** Live conversation registry once the conversation materializes. */
    private ArtifactRegistry liveArtifactRegistry;

    /**
     * Conveys a seed registry into this thinker. Asymmetric with
     * {@link #getArtifactRegistry()} by necessity: the registry a thinker reads
     * and writes is owned by its conversation, so what is set here is a SEED whose
     * artifacts merge into the conversation registry at conversation creation,
     * while the getter returns the live conversation registry once it exists
     * (the seed before that).
     */
    @Override
    public void setArtifactRegistry(ArtifactRegistry registry) {
        this.seedArtifactRegistry = registry;
    }

    @Override
    public ArtifactRegistry getArtifactRegistry() {
        return liveArtifactRegistry != null ? liveArtifactRegistry : seedArtifactRegistry;
    }

    /**
     * Subclasses call this once at conversation initialization to install whichever skills
     * the thinker declared via {@link #declareDefaultSkills()}. Each skill name is looked
     * up in {@link ai.redouble.nucleo.prompt.skill.SkillRegistry}; unknown names log a warning
     * and are skipped. Mirrors the toolRegistry seeding done in the constructor.
     */
    protected void admitConfiguredSkills(ConversationContext conversation) {
        List<String> skillNames = declareDefaultSkills();
        if (skillNames == null || skillNames.isEmpty()) {
            return;
        }
        for (String name : skillNames) {
            ai.redouble.nucleo.prompt.skill.Skill skill = ai.redouble.nucleo.prompt.skill.SkillRegistry.lookup(name);
            if (skill == null) {
                log.warn("Thinker {} declared skill '{}' which is not in SkillRegistry; skipping", getClass().getSimpleName(), name);
                continue;
            }
            conversation.addSkill(skill);
        }
    }
    protected boolean invokedAsTool = false;
    protected String conversationId;
    // Whether obtaining the conversation must find it (adopt/resume) or create it fresh
    // (minted, or the jobId-keyed default). Set only by the two identity setters.
    private boolean conversationMustExist;

    // Memory retention: how long conversations persist in memory after release.
    // No defaults here - concrete subclasses (SingleObjectiveThinker, ReactiveThinker) set their own.
    private Duration retainDuration;
    private Duration finalRetainDuration;
    /** The last run's conversation as {@link Transcript} text, taken before the conversation was released. */
    private volatile String transcript;

    /**
     * Creates a thinker with standard tool constructor.
     * This is the REQUIRED constructor for all tools.
     *
     * <p>Subclasses should implement {@link #declareDefaultTools()} to specify initial tools.
     *
     * <p>Subclasses pass the {@link ThinkerDeclaration} that names the capability floor
     * their work needs and the answer size their turns book; there is no default, and a
     * thinker without one does not compile. The deployment's picker turns the grade into
     * a model per call.
     *
     * <p>Tools can be added/removed dynamically after construction using
     * {@link #addTool(Class)}, {@link #removeTool(Class)}, and {@link #hasTool(Class)}.
     *
     * @param parent      The parent identity for lineage tracking
     * @param declaration what this seat declares: its grade and answer size, required
     */
    public AbstractThinker(Identifiable parent, ThinkerDeclaration declaration) {
        this(parent, declaration, false);
    }

    /**
     * Constructs a thinker that is bound, for its whole life, to tools that change nothing.
     *
     * <p>Mutating tools are withheld from the definitions the LLM sees, and any call to one
     * is refused at admission. Read-only is a property of a tool's declaration
     * ({@link ToolDescription#readOnly()}), which defaults to false, so an unmarked tool is
     * withheld: a tool nobody vouched for is not one a bound thinker should run.
     *
     * <p>This is what lets a caller hand over a superset. The whole toolbox of the thinkers
     * a doer supervises can be passed in wholesale, and what survives is exactly their
     * observational part - the caller does not curate, and so cannot curate wrongly. The
     * first consumer is skeptic-style supervision, where a reviewer must observe without
     * participating, but nothing here is skeptic-specific.
     *
     * <p>The binding is a constructor argument and the field is final, so it covers the
     * thinker's entire life rather than the part after someone remembered to ask for it. A
     * switch flipped later would only promise "read-only from now on", which is not the
     * property anyone wants: what makes a supervisor trustworthy is that it never ran
     * anything that changed something, not that it stopped.
     *
     * <p>The binding travels: it is carried as a {@link ReadOnlyScope} in this
     * orchestrator's scope guard, so the submission door composes it into everything
     * this thinker submits and seals it there. A delegate, an agent-as-tool, and their
     * own descendants are read-only too, transitively, without any of them arranging it.
     *
     * @param parent the parent identity for lineage tracking
     * @param forceReadOnly true to bind this thinker to tools that change nothing
     */
    protected AbstractThinker(Identifiable parent, ThinkerDeclaration declaration, boolean forceReadOnly) {
        super(parent);
        if (declaration == null) {
            throw new IllegalArgumentException(getClass().getSimpleName() + " must declare itself: a grade and an answer size,"
                    + " there is no default, silent or otherwise");
        }
        this.grade = declaration.getGrade();
        this.outputDeclaration = declaration.getOutput();
        this.comfortContextTokens = declaration.getComfortContextTokens();
        this.compactionTrigger = declaration.getCompactionTrigger();
        this.forceReadOnly = forceReadOnly;
        if (forceReadOnly) {
            ScopeGuard authored = getScopeGuard();
            setScopeGuard(authored == null ? new ScopeGuard(new ReadOnlyScope())
                    : authored.merge(new ScopeGuard(new ReadOnlyScope())));
        }
        this.toolRegistry = new ToolRegistry();
        List<Class<? extends Tool>> defaultTools = declareDefaultTools();
        if (defaultTools != null && !defaultTools.isEmpty()) {
            this.toolRegistry.registerAll(defaultTools);
        }
        List<ToolProvider> defaultProviders = declareDefaultProviders();
        if (defaultProviders != null && !defaultProviders.isEmpty()) {
            this.toolRegistry.registerAllProviders(defaultProviders);
        }
        // System-wide tools from ToolHub
        this.toolRegistry.registerAll(ToolHub.getInstance().getSystemWideTools());
        // Always register artifact access tools - lightweight, no-op if no artifacts exist
        this.toolRegistry.register(SearchArtifactContentTool.class);
        this.toolRegistry.register(GetArtifactFieldTool.class);
        // Sub-agent spawning - any thinker can delegate sub-tasks
        this.toolRegistry.register(SubThinker.class);
    }

    /**
     * Whether this thinker is bound to tools that change nothing: its own binding,
     * which construction fixed, OR one inherited from the flow that submitted it,
     * which the door sealed into the guard. Either alone is enough to bind it.
     */
    public final boolean isForceReadOnly() {
        if (forceReadOnly) {
            return true;
        }
        ScopeGuard guard = getScopeGuard();
        return guard != null && guard.getScopes().contains(new ReadOnlyScope());
    }

    /**
     * Declares which capability grade this thinker's calls need. The harness resolves the
     * actual spec per call through the deployment's picker. A thinker declares its own in
     * its {@link ThinkerDeclaration}; this setter is for a caller raising it for a
     * particular run.
     */
    @Override
    public void setGrade(Grade grade) {
        if (grade == null) {
            throw new IllegalArgumentException(getClass().getSimpleName() + ": a thinker's grade cannot be unset");
        }
        this.grade = grade;
    }

    @Override
    public Grade getGrade() {
        return grade;
    }

    /**
     * The exact entry this thinker runs on for a run, null when its grade resolves through the
     * picker. Every call the thinker spawns carries the pin, which is how a benchmark races
     * one thinker on every model of its grade.
     */
    private ModelSpec pinned;

    @Override
    public void pinModel(ModelSpec model) {
        this.pinned = model;
    }

    @Override
    public ModelSpec pinnedModel() {
        return pinned;
    }

    /**
     * The one way a thinker builds a call on its conversation: every subclass's loop goes
     * through here, so the pin and the upstream retry budget reach every call the thinker
     * makes - none can slip back to the picker, and none waits on a failing endpoint
     * longer than the thinker itself would.
     */
    protected <R> LLMCall<R> newLLMCall(Identifiable parent, ConversationContext conversation) {
        LLMCall<R> call = new LLMCall<>(parent, conversation);
        call.pinModel(pinned);
        call.setUpstreamRetries(getUpstreamRetries());
        return call;
    }

    @Override
    public boolean isInteractive() {
        return interactive;
    }

    /** Declares the answer size as a rung: the vocabulary form, chosen for the largest turn this seat produces. */
    public void setOutputSize(OutputSize size) {
        this.outputDeclaration = OutputDeclaration.of(size);
    }

    /**
     * Declares the answer size as a raw token count: the documented exception for a seat
     * whose answer fits no rung. The last declaration set wins, the same refinement a
     * subclass or parent may apply to the grade.
     */
    public void setOutputBudget(int tokens) {
        this.outputDeclaration = OutputDeclaration.of(tokens);
    }

    @Override
    public OutputDeclaration getOutputDeclaration() {
        return outputDeclaration;
    }

    /** Overrides the comfort context window for this thinker's conversation; see {@link ContextWindowManager}. */
    public void setComfortContextTokens(int comfortContextTokens) {
        this.comfortContextTokens = comfortContextTokens;
    }

    /** Overrides the compaction trigger for this thinker's conversation; see {@link ContextWindowManager}. */
    public void setCompactionTrigger(double compactionTrigger) {
        this.compactionTrigger = compactionTrigger;
    }

    /**
     * Main execution - sets up conversation and delegates to subclass-specific loop.
     */
    @Override
    public O execute(JobContext<O> context) throws LLMReadableCheckedException {
        // Get or create conversation with ownership tracking
        ConversationContext conversation = null;
        boolean shouldReleaseConversation = false;
        String conversationId = getConversationId();

        try {
            // Use the unified obtainConversation method which handles all scenarios:
            // - Gets from memory if exists
            // - Loads from persistence if available
            // - Creates new if neither exists
            // - Fully initializes in all cases (tools, callbacks, etc.)
            // - Returns a future that completes when conversation is available
            // An adopted conversationId is a request to RESUME and must already exist - a
            // resume that silently minted an empty conversation would leave the model
            // defending reasoning it can no longer see. A minted id and the jobId-keyed
            // default both ask for a fresh conversation.
            conversation = ConversationService.getInstance()
                                              .obtainConversation(conversationId, this, StringResponseHandler.instance, conversationMustExist)
                                              .join();  // Wait for conversation to become available
            shouldReleaseConversation = true;

            // Set JobContext so conversation can publish events
            conversation.setJobContext(context);
            // LLM-quality summarization for artifacts in this conversation
            conversation.setSummarizer(new LLMSummarizer(this));
            // Merge any conveyed seed into the conversation registry: same object
            // instances, refs intact - the custody hop a fan-out doer or other
            // framework caller performed before submitting this thinker.
            liveArtifactRegistry = conversation.getArtifactRegistry();
            if (seedArtifactRegistry != null && liveArtifactRegistry != null) {
                for (Artifact seeded : seedArtifactRegistry.getAllArtifacts().values()) {
                    liveArtifactRegistry.register(seeded);
                }
            }

            // Run the thinking loop (subclass specific)
            // request_tools registration and catalog assembly happen per-turn in
            // reconcileToolRegistry(), so no prompt-side catalog setup is needed here.
            runThinkingLoop(conversation, context);

            // Stash artifacts for observability while conversation is still alive
            stashArtifacts(conversation, context);

            // Return result (subclass specific)
            return getResult();

        }
        finally {
            // Release conversation if we acquired it from the service
            if (shouldReleaseConversation) {
                // what this run said, called and was told, kept on the thinker for whoever
                // holds it after the conversation itself has gone back to the service
                transcript = Transcript.render(conversation);
                Duration retain = finalRetainDuration != null ? finalRetainDuration : Duration.ZERO;
                ConversationService.getInstance().release(this, retain);
            }
        }
    }

    /**
     * The last run's conversation rendered as text - every message, tool call and tool result
     * in order, the way {@link Transcript} renders it - taken as the run ended, before the
     * conversation was released. Null before the thinker has run. A caller that holds the
     * thinker after its job completed reads here what the run did, without the conversation
     * store: a benchmark hands it to the judge, a person reads it as the trajectory.
     */
    public String transcript() {
        return transcript;
    }

    /**
     * Subclass hook to add/remove tools in {@link #toolRegistry} just before the
     * registry is read for the next LLM turn. Default implementation hides
     * {@link SubThinker} when this thinker's depth is below its
     * {@link #delegationThreshold()} (STANDARD by default, so callers at QUICK/IMMEDIATE
     * cannot delegate; THOROUGH on a SubThinker).
     *
     * <p>Called on every invocation of {@link #buildToolDefinitionBlocks()} - which
     * runs at the start of the thinking loop and after every tool-execution round -
     * so overrides have full access to the thinker's input and surrounding state
     * when deciding what to register or unregister.
     *
     * <p>Overrides should be idempotent (state-based, not action-based): the same
     * input state called twice must produce the same registry contents. Use
     * {@link ToolRegistry#register} / {@link ToolRegistry#unregister} unconditionally
     * - both are no-ops when the registry is already in the desired state.
     *
     * <p>This is the thinker's only authoritative seat for "what tools can the LLM
     * see and call" - both the prompt-side tool definitions and the dispatcher's
     * lookup table read from the same {@link #toolRegistry}, so unregistering a
     * tool here guarantees the LLM neither sees it nor can invoke it.
     */
    protected void reconcileToolRegistry() {
        if (getDepth().ordinal() >= delegationThreshold().ordinal()) {
            toolRegistry.register(SubThinker.class);
        }
        else {
            toolRegistry.unregister(SubThinker.class);
        }

        // Build request_tools with the current catalog as a typed enum inside its input
        // schema. The catalog is never injected into the prompt as free text - the LLM
        // sees it through the tool's schema, which the provider validates natively.
        Set<ToolProvider> catalog = reconcileCatalog(ToolHub.getInstance().resolveCompatibleTools(this));
        if (catalog != null && !catalog.isEmpty()) {
            toolRegistry.register(new RequestToolsProvider(catalog));
        }
        else {
            toolRegistry.unregister(RequestToolsProvider.NAME);
        }

        // The same for skills: request_skill carries the reconciled skill catalog as a typed
        // enum, and leaves the palette when there is nothing to offer.
        Set<ai.redouble.nucleo.prompt.skill.Skill> skillCatalog = reconcileSkillCatalog(ToolHub.getInstance().resolveCompatibleSkills(this));
        if (skillCatalog != null && !skillCatalog.isEmpty()) {
            toolRegistry.register(new RequestSkillProvider(skillCatalog));
        }
        else {
            toolRegistry.unregister(RequestSkillProvider.NAME);
        }
    }

    /**
     * The depth at which this thinker's palette offers {@code sub_thinker}. STANDARD for an
     * ordinary thinker, so QUICK and IMMEDIATE callers cannot delegate; {@link SubThinker}
     * raises it to THOROUGH so one level of delegation does not become an unbounded chain.
     */
    protected Depth delegationThreshold() {
        return Depth.STANDARD;
    }

    /**
     * Subclass hook to filter or modify the ToolHub compatible-tools catalog before it
     * reaches the LLM. The compatible set comes from
     * {@link ToolHub#resolveCompatibleTools(Thinker)} (declared via
     * {@link #declareCompatibleTools()} or pushed at runtime via
     * {@link ToolHub#registerCompatible}). Default returns the set unchanged.
     *
     * <p>The returned set is authoritative across BOTH catalog surfaces:
     * <ul>
     *   <li>the enum inside {@code request_tools}' own input schema (what the LLM sees as
     *       the names it may activate - the catalog never enters the prompt as free text)</li>
     *   <li>the {@link ToolHub#requestTools} admission lookup (what
     *       {@code request_tools} can actually pull in)</li>
     * </ul>
     * Removing a provider here guarantees the LLM neither sees its name nor can
     * activate it through {@code request_tools}, regardless of caching or hallucination.
     *
     * <p>Like {@link #reconcileToolRegistry()}, this gives the thinker full
     * code-level control over what its LLM may see and use, with the thinker's input
     * and surrounding state available for decisions.
     */
    protected Set<ToolProvider> reconcileCatalog(Set<ToolProvider> compatible) {
        return compatible;
    }

    /**
     * Subclass hook over the skill catalog, the counterpart of {@link #reconcileCatalog}:
     * the compatible skills from {@link ToolHub#resolveCompatibleSkills(Thinker)} before they
     * reach the {@code request_skill} schema or its admission lookup. A skill removed here is
     * neither offered nor admittable this turn. Default returns the set unchanged.
     */
    protected Set<ai.redouble.nucleo.prompt.skill.Skill> reconcileSkillCatalog(Set<ai.redouble.nucleo.prompt.skill.Skill> compatible) {
        return compatible;
    }

    /**
     * Builds tool definition blocks for available tools.
     * Runs {@link #reconcileToolRegistry()} first, then iterates {@link ToolProvider}s
     * registered in this thinker's {@link ToolRegistry}; each provider supplies its own
     * name, description, weight, and JSON Schema.
     */
    @Override
    public List<ToolDefinitionBlock> buildToolDefinitionBlocks() {
        reconcileToolRegistry();
        // After reconciliation, never inside it: whatever the hook or any override added to
        // the registry this turn, the sweep sees it, so no ordering between overrides can
        // leave a mutating tool in the offer.
        if (isForceReadOnly()) {
            ReadOnlyPalette.sweep(toolRegistry, getClass().getSimpleName());
        }
        List<ToolDefinitionBlock> blocks = new ArrayList<>();
        for (ToolProvider provider : toolRegistry.getAllProviders()) {
            String name = provider.name();
            String desc = provider.description();
            ToolWeight tw = provider.weight();
            String typeLabel = tw.type().name().toLowerCase().replace('_', ' ');
            if (tw.type() == ToolType.THINKER) {
                typeLabel = typeLabel + " L" + tw.level();
            }
            String weightLabel = tw.min() == tw.max() ? String.valueOf(tw.min()) : tw.min() + "-" + tw.max();
            // Read-only is rendered here, from the same provider the dispatcher resolves calls
            // against, so what the model is told and what admission enforces cannot diverge.
            String readOnlyLabel = provider.readOnly() ? ", read-only" : "";
            desc = desc + " [" + typeLabel + ", weight: " + weightLabel + readOnlyLabel + "]";
            blocks.add(new ToolDefinitionBlock(name, desc, modelFacingSchema(provider)));
        }
        return blocks;
    }

    /**
     * The provider's schema as a model is offered it: every artifact-typed field reduced to
     * its reference, since a model refers to an artifact and never writes one, and the
     * harness-facing {@code x-nucleo-*} keywords removed.
     * This is the one place a schema becomes model-facing - the text form and the native
     * tools API both render the block - so it is the one place the two belong. A schema
     * that will not parse is offered as it stands: the definition the model reads is not
     * the place to fail a turn over metadata.
     */
    private String modelFacingSchema(ToolProvider provider) {
        try {
            return NucleoSchemaKeywords.forModel(provider.schemaJson());
        }
        catch (IOException e) {
            log.warn("Tool {} has an unparseable schema, offering it unstripped: {}", provider.name(), e.getMessage());
            return provider.schemaJson();
        }
    }

    /**
     * Executes all requested tools and adds results to conversation. The turn's tools run
     * alongside each other: every call is submitted before any is awaited, so a turn that
     * asks for three lookups waits for the slowest one, never for their sum. The one
     * exception is a tool that acts on this turn's own conversation or palette
     * ({@link ConversationAware}, {@link ToolRegistryAware}): it completes before the next
     * call is submitted, so what it admitted or registered is what the rest of the turn
     * sees, and no two of them write the turn's state at once. All results (success and
     * error) are batched into a single outgoing message per turn to maintain proper message
     * alternation for providers with native tool calling.
     *
     * @throws Exception from an override; this body reports every tool failure into the conversation
     */
    @SuppressWarnings("RedundantThrows") // the declaration is the hook's contract; only an override throws
    protected void executeTools(List<ToolCall> toolCalls, ConversationContext conversation, JobDispatcher dispatcher, String userId, JobContext<?> context, long iteration)
            throws Exception {
        // Phase 1: submit every tool and collect handles or submission failures, in call order
        Map<ToolCall, Object> submissions = new LinkedHashMap<>();

        for (ToolCall toolCall : toolCalls) {
            try {
                // Publish tool start event
                context.publishUserProgress("Tool Starting", "Starting " + toolCall.getToolName(), 0);
                JobHandle<?> handle = submitToolCall(toolCall, dispatcher, userId, context, conversation, iteration);
                submissions.put(toolCall, handle);
            }
            catch (Exception e) {
                // Stored, and logged once by emitErrorResult in phase 2, which every stored failure reaches
                submissions.put(toolCall, e);
                notifyToolFailure(context, toolCall, e);
            }
        }

        // Phase 2: Wait for results and collect ALL into one message per turn
        OutgoingMessage<String> resultsMessage = new OutgoingMessage<>(StringResponseHandler.instance);
        resultsMessage.setRole("user");

        for (Map.Entry<ToolCall, Object> entry : submissions.entrySet()) {
            ToolCall toolCall = entry.getKey();
            Object submission = entry.getValue();

            // Check if submission failed (stored as the thrown exception in phase 1)
            if (submission instanceof Throwable submissionFailure) {
                emitErrorResult(resultsMessage, toolCall, submissionFailure, "Tool submission: " + toolCall.getToolName());
                continue;
            }

            // Submission succeeded - we have a handle
            JobHandle<?> handle = (JobHandle<?>)submission;

            try {
                // Wait for this tool to complete
                Object result = handle.get();

                // Serialize result: discovers artifacts, registers them, replaces with @ref, summarizes
                String resultJson;
                if (result != null) {
                    Summarizer summarizer = new LLMSummarizer(this);
                    resultJson = NucleoJsonSerializer.writeSummarizedWithRefs(result, conversation.getArtifactRegistry(), summarizer);
                }
                else {
                    resultJson = "[Tool returned no result]";
                }
                resultsMessage.addToolResult(toolCall.getToolUseId(), resultJson, false);

                // Report tool completion
                String completionMsg =
                        "[TOOL COMPLETED]: " + toolCall.getToolName() + " returned " + (result != null ? result.getClass().getSimpleName() : "null");
                context.publishUserNotification(UserNotificationEvent.Severity.INFO, "Tool Completed", completionMsg);
            }
            catch (Exception e) {
                // Any failure here - the tool's own failure arriving through the handle, result
                // serialization (writeSummarizedWithRefs throws IllegalStateException for an artifact
                // with no ref), or a cancelled worker - becomes exactly one error tool_result and the
                // loop continues. The model sees the error and recovers instead of the thinker dying
                // on an orphaned tool_use turn. Error (OutOfMemory, StackOverflow) is deliberately
                // NOT caught - it must propagate.
                emitErrorResult(resultsMessage, toolCall, e, "Tool execution: " + toolCall.getToolName());
                notifyToolFailure(context, toolCall, e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // Reconciliation safety net: every tool_use in this turn must be answered by exactly one
        // tool_result before the turn is sent. After parse maps each tool_use block 1:1 to a ToolCall,
        // these ids are the assistant turn's tool_use ids; any left unanswered by the loop above is
        // backfilled so the conversation can never carry an orphaned tool_use (Anthropic 400).
        Set<String> answeredIds = new HashSet<>();
        for (ContentBlock block : resultsMessage.getContentBlocks()) {
            if (block instanceof ToolResultBlock tr) {
                answeredIds.add(tr.toolUseId());
            }
        }
        for (ToolCall toolCall : toolCalls) {
            String id = toolCall.getToolUseId();
            if (id != null && !answeredIds.contains(id)) {
                log.warn("tool_use {} ({}) was not answered by the execution loop; backfilling an error result to preserve the tool_use/tool_result pairing",
                        id,
                        toolCall.getToolName());
                resultsMessage.addToolResult(id, RECONCILE_TEXT, true);
                answeredIds.add(id);
            }
        }

        // Add the single batched results message to conversation
        if (cacheAllMessages) {
            resultsMessage.setCache(true);
        }
        conversation.getMessages().add(resultsMessage);

        // Refresh tool declarations - picks up tools activated via request_tools. New names
        // reach the model as a stream announcement at the next render, so the prefix ahead
        // is untouched and the model sees the tool appear when it appeared. Declarations
        // are add-only: a drifted definition of an already-declared name is discarded.
        conversation.addTools(buildToolDefinitionBlocks());
    }

    /**
     * Emits exactly one error tool_result for a failed tool call, preserving the
     * tool_use/tool_result pairing the Anthropic API requires. Job-system wrappers
     * (ExecutionException/CompletionException) are unwrapped to the real cause; an
     * LLM-readable cause renders its own {@code explainToLLM()}, otherwise it is wrapped in
     * a {@link SystemException} under {@code componentLabel} so the message is identical to
     * what the prior inline handlers produced. The result is addressed to the tool_use id;
     * a null id cannot occur on the native path (the provider always supplies a block id),
     * so it is logged and skipped rather than injected as stray text into a turn that
     * carries tool_results.
     */
    private void emitErrorResult(OutgoingMessage<String> resultsMessage, ToolCall toolCall, Throwable cause, String componentLabel) {
        Throwable root = cause;
        if ((root instanceof ExecutionException || root instanceof CompletionException) && root.getCause() != null) {
            root = root.getCause();
        }
        String errorText;
        if (root instanceof LLMReadableException llmEx) {
            errorText = llmEx.explainToLLM();
            // Correctable = normal tool flow (the model retries): a warning with the message only.
            // Uncorrectable (SystemException, ExternalServiceException) = real failure: an error with
            // the message AND stack so it is not masked behind the generic LLM-facing text.
            if (llmEx.isCorrectable()) {
                log.warn("Tool {} failed with correctable error: {}", toolCall.getToolName(), llmEx.getLLMMessage());
            }
            else {
                log.error("Tool {} failed: {}", toolCall.getToolName(), llmEx.getLLMMessage(), root);
            }
        }
        else {
            SystemException sysEx = new SystemException(componentLabel,
                    root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName(), root);
            errorText = sysEx.explainToLLM();
            log.error("{} failed - LLM will attempt recovery", componentLabel, root);
        }
        if (toolCall.getToolUseId() != null) {
            resultsMessage.addToolResult(toolCall.getToolUseId(), errorText, true);
        }
        else {
            log.warn("Tool {} has no tool_use id; skipping its error result to avoid an orphaned turn (unreachable on the native tool-calling path)",
                    toolCall.getToolName());
        }
    }

    /**
     * What a failed tool tells whoever watches the run. A correctable failure is the model's
     * input to fix on its next turn - a warning; anything else is a failure of the tool
     * itself - an error. Said once per failed call, whether the failure came at submission
     * or through the handle.
     */
    private static void notifyToolFailure(JobContext<?> context, ToolCall toolCall, Throwable e) {
        Throwable root = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
        UserNotificationEvent.Severity severity = root instanceof LLMReadableException llm && llm.isCorrectable()
                ? UserNotificationEvent.Severity.WARNING
                : UserNotificationEvent.Severity.ERROR;
        context.publishUserNotification(severity, "Tool Failed", "Tool " + toolCall.getToolName() + " could not be executed");
    }

    /**
     * Submits a single tool call as a job and returns the handle, which the caller awaits
     * alongside the turn's other calls. A tool that acts on this turn's own conversation or
     * palette ({@link ConversationAware}, {@link ToolRegistryAware}) is awaited here instead,
     * so it has done its work before the next call is submitted; its failure then surfaces
     * from this method. Guardrails are enforced at the tool's own dispatch.
     *
     * @param toolCall     the tool call to submit
     * @param dispatcher   the job dispatcher
     * @param userId       the user ID
     * @param context      the job context
     * @param conversation the conversation of this turn: its artifact registry goes to
     *                     ArtifactRegistryAware tools, the conversation itself to ConversationAware ones
     */
    protected JobHandle<?> submitToolCall(ToolCall toolCall, JobDispatcher dispatcher, String userId, JobContext<?> context, ConversationContext conversation, long iteration)
            throws Exception {
        ArtifactRegistry artifactRegistry = conversation.getArtifactRegistry();
        // Beside validateToolCall rather than inside it, so a subclass that overrides
        // validation without chaining super cannot drop the guarantee. This is the
        // enforcement; the palette sweep only shapes what the model was offered, and the
        // registry can be widened after those definitions were built.
        if (isForceReadOnly()) {
            ReadOnlyPalette.requireReadOnly(toolRegistry, toolCall.getToolName(), getClass().getSimpleName());
        }
        // Validate before creating tool
        validateToolCall(toolCall);

        // The LLM's input could not be parsed into this tool's typed input (e.g. an invalid enum
        // value). This is a CORRECTABLE input error, not a system fault: surface its actionable reason
        // (valid options / redirect) so the model can fix the call and retry.
        if (toolCall.getParseError() != null) {
            throw toolCall.getParseError();
        }

        // Create tool instance with this thinker as parent for lineage tracking
        Tool<?, ?> tool = toolRegistry.createTool(toolCall.getToolName(), this);

        // Get the input - should already be the correct POJO type
        Object rawInput = toolCall.getInput();

        if (rawInput == null) {
            throw new SystemException("AbstractThinker", "Tool " + toolCall.getToolName() + " has null input", null);
        }

        Class<?> expectedInputType = toolRegistry.getInputTypeByToolName(toolCall.getToolName());
        if (!expectedInputType.isInstance(rawInput)) {
            throw new SystemException("AbstractThinker", "Tool " + toolCall.getToolName()
                + " input is " + rawInput.getClass().getName() + ", expected " + expectedInputType.getName()
                + ". ThinkingResponseHandler should have converted it.", null);
        }

        @SuppressWarnings("unchecked")
        Tool<Object, ?> typedTool = (Tool<Object, ?>)tool;
        typedTool.setInput(rawInput);

        // If the tool is a Thinker, mark it as being invoked as a tool
        // This prevents it from streaming directly to the user
        if (tool instanceof Thinker) {
            ((Thinker<?, ?>)tool).setInvokedAsTool(true);
        }

        // Enforce that child never exceeds parent depth. Both sides default to STANDARD
        // in ThinkerInput, so there are no null cases to handle.
        if (tool instanceof Thinker && rawInput instanceof ThinkerInput childInput && this.input instanceof ThinkerInput parentInput) {
            childInput.setDepth(Depth.min(childInput.getDepth(), parentInput.getDepth()));
        }

        // Publish enhanced start info for thinker invocations (after depth propagation)
        if (tool instanceof Thinker && rawInput instanceof ThinkerInput thinkerInput) {
            Depth depth = thinkerInput.getDepth();
            String query = thinkerInput.getQuery();
            String queryPreview = query != null ? (query.length() > 200 ? query.substring(0, 200) + "..." : query) : "";
            context.publishUserProgress("Thinker Invoked", toolCall.getToolName() + " [" + depth + "]: " + queryPreview, 0);
        }

        // Convey artifact-registry access to the tool. A non-thinker tool is not
        // an LLM context, so it gets the parent's whole registry and resolves
        // whatever refs its input names. A child thinker IS an LLM context - its
        // registry renders into its prompt every turn - so it must NOT inherit
        // the parent's whole registry. It gets only the slice the emitting
        // thinker explicitly declared via ThinkerInput.artifactRefs (resolved
        // against the parent's registry), the inbound mirror of
        // ArtifactResponse.artifactRefs that conveys artifacts back out. The
        // slice is set as a SEED that merges into the child's own conversation
        // registry when it runs.
        if (tool instanceof ArtifactRegistryAware aware && artifactRegistry != null) {
            if (tool instanceof Thinker) {
                List<String> conveyedRefs = rawInput instanceof ThinkerInput ti ? ti.getArtifactRefs() : null;
                if (conveyedRefs != null && !conveyedRefs.isEmpty()) {
                    aware.setArtifactRegistry(artifactRegistry.filter(conveyedRefs));
                }
            }
            else {
                aware.setArtifactRegistry(artifactRegistry);
            }
        }

        // If the tool needs tool registry + thinker access, inject them
        if (tool instanceof ToolRegistryAware tra) {
            tra.setToolRegistry(this.toolRegistry);
            tra.setThinker(this);
        }

        // A tool that acts on this turn's conversation gets the one this thinker holds; it must
        // not obtain the conversation itself while this thinker owns it and waits on the tool
        if (tool instanceof ConversationAware aware) {
            aware.setConversation(conversation);
        }

        // Guardrails are framework-enforced at the tool's own dispatch (input side before
        // its resources are allocated, output side before its result is delivered), so
        // submission here carries no guardrail ceremony and no route-specific protection.
        JobHandle<?> toolHandle = submitInIteration(iteration, tool);

        // A tool that writes the turn's own state finishes before the next call is
        // submitted; every other tool runs alongside its siblings and is awaited by the turn
        if (tool instanceof ConversationAware || tool instanceof ToolRegistryAware) {
            toolHandle.get();
        }

        return toolHandle;
    }

    /**
     * The per-turn compaction check: warns the user near the window, and when the trigger is
     * crossed runs {@link ContextWindowManager#ensureFits}, the one ladder the framework has
     * (LIGHT, MODERATE and AGGRESSIVE best-effort, MAXIMUM loud). On overflow the refusal
     * propagates after a diagnostic dump of what filled the window.
     *
     * @param conversation the conversation to potentially compact
     * @param context      the job context for publishing notifications
     * @param iteration    the current thinking iteration number
     * @return the original conversation if no compaction needed, or a compacted version
     * @throws Exception if compaction fails or context cannot be made to fit
     */
    protected ConversationContext performCompactionLoop(ConversationContext conversation, JobContext<?> context, int iteration) throws Exception {

        ModelSpec accountingSpec = accountingSpec(conversation);
        if (accountingSpec == null) {
            // Never resolved and no prior: a fresh conversation with no history to compact
            return conversation;
        }
        ContextWindowManager manager = new ContextWindowManager(accountingSpec, this, comfortContextTokens, compactionTrigger);
        double usage = manager.getUsagePercentage(conversation);

        // Early warning for ALL conversations (compactable or not)
        if (usage >= 0.80) {
            int total = conversation.getTotalTokens(accountingSpec);
            context.publishUserNotification(UserNotificationEvent.Severity.WARNING, "Context Window Alert", String.format("Conversation is at %.0f%% of context window (%s tokens). Approaching limit.",
                    usage * 100, Formats.compactNumber(total)));
            log.warn("Context at {}% of window ({} tokens)", Math.round(usage * 100), Formats.compactNumber(total));
        }

        // Skip when nothing to do: below the trigger, or not compactable at all
        if (!manager.needsCompaction(conversation)) {
            return conversation;
        }

        // Notify user
        log.info("Starting compaction at iteration {}", iteration);
        context.publishUserNotification(UserNotificationEvent.Severity.INFO, "Context Compaction", "Context approaching limit, starting compaction");

        try {
            return manager.ensureFits(conversation);
        }
        catch (ContextOverflowException overflow) {
            dumpContextForDiagnosis(conversation, accountingSpec);
            throw overflow;
        }
    }

    /** The full context breakdown at DEBUG, for diagnosing what filled an overflowing window. */
    private void dumpContextForDiagnosis(ConversationContext conversation, ModelSpec accountingSpec) {
        log.debug("=== CONTEXT OVERFLOW DUMP START ===");
        for (var block : conversation.getMainObjectiveBlocks()) {
            if (block instanceof TextBlock(String text)) {
                log.debug("MAIN_OBJECTIVE TextBlock ({} chars): {}", text.length(), text);
            }
            else if (block instanceof ai.redouble.nucleo.harness.conversation.ContentBlocks.ToolDefinitionBlock td) {
                log.debug("MAIN_OBJECTIVE ToolDef: {} ({} chars)", td.name(), td.schemaJson().length());
            }
            else {
                log.debug("MAIN_OBJECTIVE block: {}", block.getClass().getSimpleName());
            }
        }
        for (var msg : conversation.getMessages()) {
            log.debug("MESSAGE [{}] ({} tokens)", msg.getRole(), msg.getEstimatedTokens(accountingSpec));
        }
        var artifacts = conversation.getArtifactRegistry().getAllArtifacts();
        log.debug("ARTIFACT_REGISTRY: {} artifacts", artifacts.size());
        for (var entry : artifacts.entrySet()) {
            String json = NucleoJsonSerializer.write(entry.getValue());
            log.debug("ARTIFACT [{}] ({} chars): {}", entry.getKey(), json.length(), json);
        }
        log.debug("=== CONTEXT OVERFLOW DUMP END ===");
    }

    /**
     * Forces aggressive compaction of a conversation.
     * Unlike performCompactionLoop, this method:
     * - Does NOT check isCompactable() - forces compaction regardless
     * - Does NOT check needsCompaction() - assumes compaction is needed
     * - Goes directly to AGGRESSIVE level
     * <p>
     * Use this when a TokenEstimateExceedsLimitException has been thrown
     * and immediate compaction is required to continue.
     *
     * @param conversation the conversation to compact
     * @param context      the job context for publishing notifications
     * @return the compacted conversation
     * @throws Exception from an override's compaction
     */
    @SuppressWarnings("RedundantThrows") // the declaration is the hook's contract; only an override throws
    protected ConversationContext forceCompaction(ConversationContext conversation, JobContext<?> context) throws Exception {
        ModelSpec accountingSpec = accountingSpec(conversation);
        if (accountingSpec == null) {
            throw new IllegalStateException("Forced compaction on a conversation that never resolved a model and has no prior"
                    + " - there is no spec to account tokens under");
        }
        int beforeTokens = conversation.getTotalTokens(accountingSpec);
        log.warn("Forcing aggressive compaction on {} tokens", Formats.compactNumber(beforeTokens));

        // Use aggressive compaction directly
        ContextCompactor compactor = new LLMContextCompactor(this);
        ConversationContext compacted = compactor.compact(conversation, CompactionLevel.AGGRESSIVE);
        int afterTokens = compacted.getTotalTokens(accountingSpec);
        log.info("Forced compaction: {} -> {} tokens (saved {})",
                Formats.compactNumber(beforeTokens),
                Formats.compactNumber(afterTokens),
                Formats.compactNumber(beforeTokens - afterTokens));
        context.publishUserNotification(UserNotificationEvent.Severity.INFO, "Compaction Complete", String.format("Reduced context from %s to %s tokens", Formats.compactNumber(beforeTokens), Formats.compactNumber(afterTokens)));
        return compacted;
    }

    // ================ Overridable Methods ================

    /**
     * The spec token accounting runs under: the conversation's resolved binding when one
     * exists, else the spec that served it before (sticky conversations almost always
     * re-resolve to their prior). Null only for a conversation that never resolved and
     * carries no prior - which also has no history worth accounting.
     */
    private ModelSpec accountingSpec(ConversationContext conversation) {
        if (conversation.isModelResolved()) {
            return conversation.getModel();
        }
        return Models.findSpec(conversation.getPriorSpecId());
    }

    /**
     * Validates a tool call before submission.
     *
     * <p>Template method hook for subclasses to enforce validation rules.
     * Default implementation does nothing.
     *
     * @param toolCall the tool call to validate
     * @throws GuardrailException if validation fails
     */
    @SuppressWarnings("RedundantThrows") // the declaration is the hook's contract; only an override throws
    protected void validateToolCall(ToolCall toolCall) throws GuardrailException {
        // Default: no validation
    }

    /**
     * Adds a tool to this thinker's available tools.
     * The tool will be registered in the tool registry and can be invoked by the LLM.
     *
     * @param toolClass the tool class to add
     */
    public void addTool(Class<? extends Tool> toolClass) {
        toolRegistry.register(toolClass);
    }

    /**
     * Adds a provider directly (non-class-backed, e.g. MCP).
     */
    @Override
    public void addTool(ToolProvider provider) {
        toolRegistry.register(provider);
    }

    /**
     * Removes a tool from this thinker's available tools.
     * The tool will be unregistered from the tool registry.
     *
     * @param toolClass the tool class to remove
     */
    public void removeTool(Class<? extends Tool> toolClass) {
        toolRegistry.unregister(toolClass);
    }

    /**
     * Removes a registered tool by name.
     */
    @Override
    public void removeTool(String toolName) {
        toolRegistry.unregister(toolName);
    }

    /**
     * Checks if a tool class is registered with this thinker.
     *
     * @param toolClass the tool class to check
     * @return true if the tool is registered
     */
    public boolean hasTool(Class<? extends Tool> toolClass) {
        return toolRegistry.hasToolClass(toolClass);
    }

    /**
     * Checks if a tool by name is registered with this thinker.
     */
    @Override
    public boolean hasTool(String toolName) {
        return toolRegistry.hasTool(toolName);
    }

    /**
     * Gets all currently registered tool classes. Synthesized from the registered
     * providers' {@link ToolProvider#toolClass()}.
     *
     * @return unmodifiable collection of tool classes
     */
    public Collection<Class<? extends Tool>> getTools() {
        List<Class<? extends Tool>> classes = new ArrayList<>();
        for (ToolProvider provider : toolRegistry.getAllProviders()) {
            classes.add(provider.toolClass());
        }
        return Collections.unmodifiableList(classes);
    }

    /**
     * Gets all currently registered providers.
     */
    @Override
    public Collection<ToolProvider> getProviders() {
        return toolRegistry.getAllProviders();
    }

    // ================ Abstract Methods ================

    /**
     * Declares the core tools this thinker always has loaded with full schemas.
     * Called during construction to initialize the tool registry.
     *
     * @return List of tool classes, or empty list if no tools needed
     */
    protected abstract List<Class<? extends Tool>> declareDefaultTools();

    /**
     * Declares non-class-backed providers this thinker always has loaded. Called during
     * construction alongside {@link #declareDefaultTools()}. Default empty so existing
     * thinkers compile unchanged. Override to attach MCP-bound providers (or any other
     * non-class-backed provider) at thinker construction.
     *
     * @return list of providers, or empty list if none
     */
    protected List<ToolProvider> declareDefaultProviders() {
        return List.of();
    }

    /**
     * Declares the names of classpath-resident skills this thinker wants admitted into
     * every new conversation. Names are looked up against {@link ai.redouble.nucleo.prompt.skill.SkillRegistry}
     * at conversation init by {@link #admitConfiguredSkills(ConversationContext)}; unknown
     * names log a warning and are skipped.
     *
     * <p>Mirrors {@link #declareDefaultTools()} - return a list of identifiers to install,
     * empty list (the default) means no skills. Skills the model may pull in itself, at run
     * time, are declared through {@link #declareCompatibleSkills()} instead.
     *
     * @return List of skill names to admit, or empty list if no skills needed.
     */
    protected List<String> declareDefaultSkills() {
        return List.of();
    }

    /**
     * Declares the skills this thinker's model may admit into the conversation at run time,
     * subject to admission, the counterpart of {@link #declareCompatibleTools()}. The
     * catalog appears as the enumerated {@code skillNames} of the {@code request_skill}
     * tool; the model admits one by name and its instructions ride every later call.
     *
     * <p>Override to name skills, a bundle, or everything the registry holds:
     * <pre>
     * protected SkillSelector declareCompatibleSkills() {
     *     SkillSelector sel = new SkillSelector("delegation");
     *     sel.addBundle("ai.redouble.skills.");
     *     return sel;
     * }
     * </pre>
     *
     * @return SkillSelector with compatible skills, or null if none
     */
    protected SkillSelector declareCompatibleSkills() {
        return null;
    }

    /**
     * Declares additional tools this thinker may use, subject to admission.
     * The catalog (name + description) appears in the system prompt.
     * The LLM activates specific tools on demand via request_tools.
     *
     * <p>Override to declare packages and/or individual tool classes:
     * <pre>
     * protected ToolSelector declareCompatibleTools() {
     *     ToolSelector sel = new ToolSelector("ai.redouble.nucleo.ext.lit");
     *     sel.addToolPackage("ai.redouble.nucleo.ext.patent.epo");
     *     sel.addClass(SomeSpecificTool.class);
     *     return sel;
     * }
     * </pre>
     *
     * @return ToolSelector with compatible tools, or null if none
     */
    protected ToolSelector declareCompatibleTools() {
        return null;
    }

    /**
     * Runs the main thinking loop - subclass specific implementation.
     *
     * @param conversation the conversation context with messages
     * @param context      the job context for progress and events
     * @throws LLMReadableCheckedException if processing fails
     */
    protected abstract void runThinkingLoop(ConversationContext conversation, JobContext<O> context) throws LLMReadableCheckedException;

    /**
     * The validation guards run against each candidate final answer, consulted once per
     * candidate. A refusal is fed back into the thinker's own conversation as a correction turn
     * and the candidate is dropped, so the model that produced the answer is the one that fixes
     * it, with its full context. The default declares none.
     */
    protected List<ValidationGuardrail<? super O>> declareValidationGuardrails() {
        return List.of();
    }

    /**
     * Feeds a correctable error back to the model as the next user turn, in the one shape every
     * seat that corrects uses: the LLM-readable explanation and nothing else.
     */
    protected void appendCorrection(ConversationContext conversation, LLMReadableException error) {
        OutgoingMessage<String> correction = new OutgoingMessage<>(StringResponseHandler.instance);
        correction.addText(error.explainToLLM());
        conversation.getMessages().add(correction);
    }

    /**
     * What every agentic loop does with a failure from its own model call: a failure the model
     * can fix becomes the next turn's correction, and the loop asks again. A tool that fails
     * this way never reaches here - {@link #executeTools} already answers each tool call with
     * its own outcome - so this is the seam for a reply the model got wrong in a way
     * {@code LLMCall} could not correct within its own budget: an answer of a shape the schema
     * cannot hold, or one its guardrails refused.
     * <p>
     * The classification is not a loop's business to invent, which is why it lives here rather
     * than in each loop: a correctable failure means the same thing to every seat, and a seat
     * that answers a human differs from one that answers a parent thinker only in what it does
     * once corrections are exhausted.
     *
     * @return true when a correction was appended and the caller should run another iteration;
     *         false when the failure is not the model's to fix, leaving the caller to fail
     */
    protected boolean correctableFailureAppended(ExecutionException e, ConversationContext conversation, int iteration) {
        if (e.getCause() instanceof LLMReadableException llmEx && llmEx.isCorrectable()) {
            log.warn("Correctable LLM error (iteration {}): {}", iteration, llmEx.getLLMMessage());
            appendCorrection(conversation, llmEx);
            return true;
        }
        return false;
    }

    /** One model call of a loop, made on whichever conversation the loop currently holds. */
    @FunctionalInterface
    protected interface ModelCall<R> {
        R submit(ConversationContext conversation) throws ExecutionException, InterruptedException;
    }

    /** The reply to a call retried after compaction, and the compacted conversation the loop continues on. */
    protected record CompactedReply<R>(ConversationContext conversation, R response) {}

    /**
     * What every agentic loop does when its call's token estimate overflows the model: tells the
     * user, compacts the conversation aggressively, and makes the call once more on it. An
     * overflow on that retry ends the run with a {@link ContextOverflowException}, so a parent
     * thinker learns the conversation cannot fit; any other failure of the retry propagates as
     * it came.
     */
    protected <R> CompactedReply<R> compactAndRetry(TokenEstimateExceedsLimitException overflow, ConversationContext conversation,
                                                    JobContext<?> context, ModelCall<R> call) throws Exception {
        log.warn("Token overflow detected: {} - attempting compaction", overflow.getMessage());
        context.publishUserNotification(UserNotificationEvent.Severity.WARNING, "Context Too Large",
                "Conversation exceeds model limit. Compacting and retrying...");
        ConversationContext compacted = forceCompaction(conversation, context);
        try {
            return new CompactedReply<>(compacted, call.submit(compacted));
        }
        catch (ExecutionException retryEx) {
            if (retryEx.getCause() instanceof TokenEstimateExceedsLimitException retryOverflow) {
                log.error("Context still too large after compaction: {}", retryOverflow.getMessage());
                context.publishUserNotification(UserNotificationEvent.Severity.ERROR, "Conversation Too Large",
                        "Even after compaction, the conversation exceeds the model's limit. "
                                + "Please start a new conversation or reduce the amount of context.");
                throw new ContextOverflowException("Context exceeds limit after compaction: " + retryOverflow.getMessage(), retryOverflow);
            }
            throw retryEx;
        }
    }


    /**
     * Stashes artifact registry contents on the job context for observability.
     * Called after the thinking loop completes, while the conversation is still alive.
     * Uses summarized serialization (no LLM calls beyond the cached summaries).
     */
    private void stashArtifacts(ConversationContext conversation, JobContext<O> context) {
        try {
            String allJson = serializeArtifactsAsArray(conversation.getArtifactRegistry().getAllArtifacts().values());
            if (allJson != null) {
                context.putMetadata(OBS_ARTIFACTS_IN, allJson);
            }
            O result = getResult();
            if (result != null) {
                String outJson = serializeArtifactsAsArray(result.getArtifacts());
                if (outJson != null) {
                    context.putMetadata(OBS_ARTIFACTS_OUT, outJson);
                }
            }
        }
        catch (Exception e) {
            log.warn("Failed to serialize thinker artifacts for observability: {}", e.getMessage());
        }
    }

    private String serializeArtifactsAsArray(java.util.Collection<? extends Artifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (Artifact artifact : artifacts) {
            if (!first) sb.append(",");
            sb.append(NucleoJsonSerializer.writeSummarizedCompact(artifact));
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Gets the final result - subclass specific.
     *
     * @return the result of type O, or null if no result
     */
    protected abstract O getResult();

    @Override
    public boolean cacheAllMessages() {
        return cacheAllMessages;
    }

    @Override
    public void setCacheAllMessages(boolean cacheAllMessages) {
        this.cacheAllMessages = cacheAllMessages;
    }

    @Override
    public boolean isInvokedAsTool() {
        return invokedAsTool;
    }

    @Override
    public void setInvokedAsTool(boolean invokedAsTool) {
        this.invokedAsTool = invokedAsTool;
    }

    @Override
    public Duration getRetainDuration() {
        return retainDuration;
    }

    @Override
    public void setRetainDuration(Duration retainDuration) {
        this.retainDuration = retainDuration;
    }

    @Override
    public Duration getFinalRetainDuration() {
        return finalRetainDuration;
    }

    @Override
    public void setFinalRetainDuration(Duration finalRetainDuration) {
        this.finalRetainDuration = finalRetainDuration;
    }

    @Override
    public String getConversationId() {
        return conversationId != null ? conversationId : getJobId();
    }

    @Override
    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
        this.conversationMustExist = true;
    }

    @Override
    public String mintConversationId() {
        // Same readable shape as job ids, with the conv marker so a minted id is
        // never mistaken for a legacy jobId-derived conversation id
        this.conversationId = getClass().getSimpleName() + "-conv-" + randomIdSuffix();
        this.conversationMustExist = false;
        return this.conversationId;
    }

    @Override
    public boolean isConversationMinted() {
        return conversationId != null && !conversationMustExist;
    }

}