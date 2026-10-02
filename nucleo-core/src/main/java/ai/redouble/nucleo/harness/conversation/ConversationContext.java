/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import org.slf4j.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

/**
 * Represents the conversation context for tool orchestration.
 * Contains the main objective (if known) and the message history.
 *
 * <p>This class is fully serializable and can be stored in various ways:
 * <ul>
 *   <li>In-memory (e.g., Tomcat session) - ResponseHandlers remain intact</li>
 *   <li>Persistent storage (database, file) - ResponseHandlers become null (dehydrated)</li>
 * </ul>
 *
 * <p>After deserialization from persistent storage, messages will be in a dehydrated state
 * where ResponseHandlers are null. This is transparent for most operations since handlers
 * are only needed for active request/response cycles, not for historical messages.
 *
 * <h2>Token Tracking for Context Window Management</h2>
 * <p>{@link #getTotalTokens(ModelSpec)} is the context-window number: a fresh estimate
 * from scratch using the tokenizer, for compaction and overflow decisions. For billing
 * and cache metrics use {@link ai.redouble.nucleo.harness.llm.LLMResponse}, which tracks
 * billable tokens (with cache pricing adjustments) and cache performance.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-19)
 */
public class ConversationContext implements Serializable {
    private static final Logger log = LoggerFactory.getLogger(ConversationContext.class);

    /** Objective-map key of the schema-notation legend, placed at render when the request's handler declares {@code usesSchemaNotation()}. */
    public static final String KEY_LEGEND = "legend";
    /** Objective-map key of the tool-annotation guidance, put when declared tools first render. */
    public static final String KEY_TOOL_WEIGHTS = "toolWeights";
    /** Objective-map key prefix under which a rendered tool definition lives in the head. */
    public static final String TOOL_KEY_PREFIX = "tool:";

    /**
     * LLM-facing guidance explaining the @-prefixed schema notation that
     * {@code ai.redouble.nucleo.harness.schema.PojoDefinition} renders into response contracts.
     * The legend follows the contract - the notation's only producer, tool schemas are
     * standard JSON Schema - not the conversation: {@link #reconcileLegend()} places it
     * under {@link #KEY_LEGEND} exactly when an outgoing message's handler declares
     * {@code usesSchemaNotation()}, and a plain-prose conversation never carries it. A
     * reader given the notation without the legend takes the schema literally -
     * returning the descriptor shape instead of an instance of it - and never produces
     * a recognizable final answer.
     */
    public static final String SCHEMA_NOTATION_GUIDANCE =
            "Schema notation. In any schema you are given, keys beginning with @ are there to explain the "
            + "structure to YOU and provide hints on object construction. They are never a part of your reply. "
            + "@type names a value's type, @description its meaning, @required that it must be present, "
            + "@examples sample values, @values the only values an enum accepts, @fields the members of an object.\n"
            + "Answer with an instance of the schema. At every level, a node with @fields becomes an object "
            + "holding those members, the same rule applying inside it; a node without @fields becomes a value "
            + "of its @type. The name in @type (Person, Address) tells you which shape you are building.\n"
            + "schema    {\"@type\":\"Person\",\"@fields\":{\n"
            + "            \"name\":{\"@type\":\"string\",\"@description\":\"full name\"},\n"
            + "            \"tags\":{\"@type\":\"array of string\"},\n"
            + "            \"home\":{\"@type\":\"Address\",\"@fields\":{\"city\":{\"@type\":\"string\"}}}}}\n"
            + "instance  {\"name\":\"Ada Lovelace\",\"tags\":[\"mathematician\"],\"home\":{\"city\":\"London\"}}";

    /**
     * LLM-facing guidance explaining the tool annotation format. Rendered into the head
     * under {@link #KEY_TOOL_WEIGHTS} just before the first tool definitions, so the model
     * understands what [type, weight: N] means and how to use it.
     */
    public static final String TOOL_WEIGHT_GUIDANCE =
            "Each tool is annotated [type, weight: N]. " + "Types: 'in memory' = instant computation, no external calls. "
            + "'api call' = single external API call. " + "'thinker LN' = LLM-driven sub-agent (N = depth of sub-agent hierarchy beneath it). "
            + "Weight is relative computational cost on a 0-100 scale. "
            + "Low weight (1-5) = quick lookups. High weight (30+) = deep multi-step investigations. "
            + "Match tool choice to your depth: at IMMEDIATE/QUICK, avoid thinkers and high-weight tools entirely. "
            + "At STANDARD, use tools freely but prefer lower-weight options when sufficient. "
            + "Only invoke high-weight thinkers (30+) when depth is THOROUGH or above.";

    /**
     * The main objective as an insertion-ordered map of named slots. The values, in
     * insertion order, ARE the system content; keys never reach the wire. A key is a
     * slot: re-putting it replaces the value in place (a {@link LinkedHashMap} re-put
     * keeps the original position, so a refresh never reorders the rendered prefix).
     * A caller that wants append semantics instead generates a fresh key per put.
     */
    private final LinkedHashMap<String, ContentBlock> mainObjective = new LinkedHashMap<>();

    /**
     * Tools declared to this conversation, by name, in declaration order. Add-only:
     * a name already declared is never replaced, whatever the new declaration would
     * produce - definitions are immutable once declared. Conversion to wire definitions
     * happens at the first render after each declaration, never eagerly
     * ({@link #reconcileDeclaredTools()}).
     */
    private final LinkedHashMap<String, DeclaredTool> declaredTools = new LinkedHashMap<>();

    /**
     * Whether this instance has rendered at least once ({@link #prepareMessagesForLLM}).
     * A frozen head is immutable: divergent objective puts and nukes are refused (loudly
     * logged no-ops), and newly declared tools reach the model through the message stream
     * instead of the head. Instance-lifetime only, never persisted: a restored
     * conversation starts unfrozen, so composition (and the use-time refresh of framework
     * slots) is legal again until its own first render - correctly free, because a fresh
     * instance has no warm provider cache to lose.
     */
    private transient boolean frozen;
    private final List<ai.redouble.nucleo.prompt.skill.Skill> loadedSkills = new java.util.concurrent.CopyOnWriteArrayList<>();
    /**
     * The deferred model binding: minted by the job's {@code requireModel} declaration,
     * wired here via {@link #setModelBinding}, resolved by the harness before execution.
     * Transient: a deserialized conversation carries only the declaration fields below
     * plus {@link #priorSpecId}, and gets a fresh binding at the next attempt.
     */
    private transient ModelBinding modelBinding;
    // Declaration fields: what this conversation's seat asks for, readable before any
    // resolution has happened. The binding is the answer; these are the question.
    private Grade grade;
    private boolean interactive;
    /**
     * Id of the spec that served this conversation last, stamped by
     * {@code ModelBinding.resolve}. Survives persistence and serialization so stickiness
     * (and the compaction accounting fallback) works across restarts.
     */
    private String priorSpecId;
    private boolean cacheMainObjective = true;
    private final List<Message> messages = new ArrayList<>();
    private final ArtifactRegistry artifactRegistry = new ArtifactRegistry();
    private String title;
    private String userId;
    private boolean temporary = true;
    // The seat's effort and answer-size declarations, stamped by ConversationService from the
    // thinker that obtains this conversation, or set by the job that builds it by hand. Null
    // here means the wired binding declares them; nothing declaring anywhere is a bug.
    private Depth depth;
    private OutputDeclaration outputDeclaration;

    private transient JobContext<?> jobContext;
    /**
     * Summarizer used when serializing this conversation's content for the LLM.
     * Set by the owning thinker (typically the LLM-backed summarizer in
     * {@code tools.builtin} with the thinker as parent).
     * Transient because it holds a runtime identifiable; null after restore until reset.
     */
    private transient Summarizer summarizer;
    // Persistence fields
    private final String conversationId;
    private String workflowId;
    private boolean compactable = true;
    private Instant createdAt;
    private transient Consumer<ConversationContext> saveCallback;

    public ConversationContext() {
        this.conversationId = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    /** A conversation under a caller-chosen id: the service's create and the snapshot restore. */
    public ConversationContext(String conversationId) {
        this.conversationId = conversationId;
        this.createdAt = Instant.now();
    }

    /**
     * A one-turn conversation for a call that is a task, not a dialogue: the model given, one
     * user message carrying the prompt and answered as text, thinking off, and the answer capped
     * at {@code outputBackstopTokens}. Thinking is off because a reasoning budget on a precise
     * extraction is what sends small models into counting spirals. It is built fresh per call on
     * purpose: a client's truncation escalation raises the cap on the message it sent, and a
     * conversation kept for the next call would carry that raised cap into it.
     */
    public static ConversationContext singleTurn(ModelSpec model, String prompt, int outputBackstopTokens) {
        ConversationContext conversation = new ConversationContext();
        conversation.setModelBinding(ModelBinding.preResolved(model));
        conversation.setDepth(Depth.IMMEDIATE);
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.addText(prompt);
        message.setRequestedOutputTokens(outputBackstopTokens);
        conversation.getMessages().add(message);
        return conversation;
    }

    /**
     * Sets the JobContext to enable event publishing.
     * This should be set when the conversation is actively being used by a job.
     *
     * @param jobContext the job context for publishing events
     */
    public void setJobContext(JobContext<?> jobContext) {
        this.jobContext = jobContext;
    }

    /**
     * Sets the summarizer used when content is serialized for the LLM.
     * The owning thinker should call this with the LLM-backed summarizer
     * ({@code ai.redouble.nucleo.tools.builtin.LLMSummarizer}) so the artifact
     * registry gets LLM-quality summaries instead of plain truncation.
     */
    public void setSummarizer(Summarizer summarizer) {
        this.summarizer = summarizer;
    }

    public Summarizer getSummarizer() {
        return summarizer;
    }

    /**
     * Gets the JobContext associated with this conversation.
     * Returns the coordinator's context (e.g., Thinker) if set, null otherwise.
     *
     * @return the job context, or null if not set
     */
    public JobContext<?> getJobContext() {
        return jobContext;
    }

    /**
     * The spec serving this conversation - delegation to the wired {@link ModelBinding}.
     * Throws before a binding is wired and resolved: pre-resolution model dependence is a
     * bug this contract surfaces loudly instead of returning a stale spec.
     */
    public ModelSpec getModel() {
        if (modelBinding == null) {
            throw new UncorrectableRuntimeLLMException("Conversation " + conversationId
                    + " has no model binding wired. Jobs declare their need via requireModel and wire the"
                    + " returned binding with setModelBinding; the harness resolves it before execution.");
        }
        return modelBinding.getModel();
    }

    /** Whether a binding is wired AND resolved - the non-throwing probe for accounting and persistence. */
    public boolean isModelResolved() {
        return modelBinding != null && modelBinding.isResolved();
    }

    public ModelBinding getModelBinding() {
        return modelBinding;
    }

    /** Wires the binding both ways: the binding onto this context AND this context into the binding. */
    public void setModelBinding(ModelBinding binding) {
        this.modelBinding = binding;
        if (binding != null) {
            binding.attachConversation(this);
        }
    }

    public Grade getGrade() {
        return grade;
    }

    public void setGrade(Grade grade) {
        this.grade = grade;
    }

    public boolean isInteractive() {
        return interactive;
    }

    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
    }

    public String getPriorSpecId() {
        return priorSpecId;
    }

    public void setPriorSpecId(String priorSpecId) {
        this.priorSpecId = priorSpecId;
    }

    /**
     * Gets the conversation title.
     * If no title is set, generates one from the first user message (first 50 characters).
     *
     * @return the conversation title
     */
    public String getTitle() {
        if (title == null || title.isEmpty()) {
            String utterance = firstUserUtterance();
            if (utterance != null) {
                title = utterance.length() <= 50 ? utterance : utterance.substring(0, 50) + "...";
            }
            if (title == null) {
                title = "Untitled Conversation";
            }
        }
        return title;
    }

    /**
     * The first real user utterance of this conversation, or null before one exists.
     * The single source for creation-time enrichment: a stored conversation is titled
     * and embedded from what the user first asked, exactly once. Application-authored
     * context riding the user channel ({@link Message#isAppAuthored()}, e.g. a scoped
     * preamble injected at initialization) is not an utterance and is skipped.
     */
    public String firstUserUtterance() {
        for (Message message : messages) {
            if ("user".equals(message.getRole()) && !message.isAppAuthored()) {
                String content = message.getRawContent();
                if (content != null && !content.isEmpty()) {
                    return content;
                }
            }
        }
        return null;
    }

    /**
     * Sets the conversation title.
     *
     * @param title the conversation title
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * Gets the user ID who owns this conversation.
     *
     * @return the user ID
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the user ID who owns this conversation.
     *
     * @param userId the user ID
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Checks if this conversation is temporary.
     * Temporary conversations are not saved to persistent storage.
     *
     * @return true if temporary
     */
    public boolean isTemporary() {
        return temporary;
    }

    /**
     * Sets whether this conversation is temporary.
     *
     * @param temporary true if temporary
     */
    public void setTemporary(boolean temporary) {
        this.temporary = temporary;
    }

    /**
     * Gets the unique conversation ID.
     *
     * @return the conversation ID
     */
    public String getConversationId() {
        return conversationId;
    }

    /**
     * Gets the workflow ID associated with this conversation.
     *
     * @return the workflow ID
     */
    public String getWorkflowId() {
        return workflowId;
    }

    /**
     * Sets the workflow ID associated with this conversation.
     *
     * @param workflowId the workflow ID
     */
    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    /**
     * Checks if this conversation can be compacted.
     * Non-compactable conversations preserve full history.
     *
     * @return true if compactable
     */
    public boolean isCompactable() {
        return compactable;
    }

    /**
     * Sets whether this conversation can be compacted.
     *
     * @param compactable true if compactable
     */
    public void setCompactable(boolean compactable) {
        this.compactable = compactable;
    }

    /**
     * Gets the creation timestamp of this conversation.
     *
     * @return the creation timestamp
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Sets the creation timestamp (used during deserialization).
     *
     * @param createdAt the creation timestamp
     */
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Declares a tool to this conversation. Add-only and safe from anywhere: a name
     * already declared is a no-op, whatever the new declaration would produce, so
     * per-round refreshes and belt-and-suspenders declarations cost nothing. Tools are
     * never redefined or removed; conversion to a wire definition happens at the first
     * render after the declaration ({@link #reconcileDeclaredTools()}), so churn before
     * that render is free.
     */
    public void addTool(DeclaredTool tool) {
        declaredTools.putIfAbsent(tool.name(), tool);
    }

    /** Declares every tool in the collection; see {@link #addTool(DeclaredTool)}. */
    public void addTools(Collection<? extends DeclaredTool> tools) {
        for (DeclaredTool tool : tools) {
            addTool(tool);
        }
    }

    /**
     * Ensures the schema-notation legend accompanies a conversation whose request
     * describes structure in the framework's {@code @}-notation. The trigger is the
     * response contract - an outgoing message whose handler declares
     * {@code usesSchemaNotation()} - never the conversation itself or its tools (tool
     * schemas are standard JSON Schema): a plain-prose conversation carries no legend.
     * Pre-freeze the legend is a head slot, re-put fresh so a restored conversation
     * self-heals to the current text; if the notation first appears after the freeze,
     * the legend is a stream message at the point of appearance, exactly like a
     * late-declared tool. Presence is derived from the conversation content, never a
     * side field.
     */
    private void reconcileLegend() {
        boolean needed = false;
        for (Message message : messages) {
            if (message instanceof OutgoingMessage<?> outgoing
                && outgoing.getResponseHandler() != null
                && outgoing.getResponseHandler().usesSchemaNotation()) {
                needed = true;
                break;
            }
        }
        if (!needed) {
            return;
        }
        if (!frozen) {
            mainObjective.put(KEY_LEGEND, new TextBlock(SCHEMA_NOTATION_GUIDANCE));
            return;
        }
        if (mainObjective.containsKey(KEY_LEGEND) || legendInStream()) {
            return;
        }
        OutgoingMessage<Void> note = new OutgoingMessage<>(null);
        note.setRole("user");
        note.setTimestamp(Instant.now());
        note.addText(SCHEMA_NOTATION_GUIDANCE);
        messages.add(note);
    }

    /** Whether a prior stream message already carries the legend; the text is a fixed constant, so containment is deterministic. */
    private boolean legendInStream() {
        for (Message message : messages) {
            if (message instanceof OutgoingMessage<?> outgoing) {
                for (ContentBlock block : outgoing.getContentBlocks()) {
                    if (block instanceof TextBlock(String text) && text.contains(SCHEMA_NOTATION_GUIDANCE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Brings what the model has been told in line with what has been declared. Runs at
     * the top of every {@link #prepareMessagesForLLM} call; declared tools the model has
     * not seen convert to wire definitions here - once per tool, never eagerly.
     *
     * <p>Where they land depends on whether this instance has rendered before. At the
     * first render the palette is stable head content and lives with the instructions
     * (under {@link #TOOL_KEY_PREFIX} keys, preceded by the {@link #KEY_TOOL_WEIGHTS}
     * guidance): it belongs to the cacheable prefix, and opening the conversation with a
     * wall of schemas primes a weak model to answer in schema form. After that the head
     * is frozen, and a newly declared tool is a message at the point of declaration, so
     * the prefix ahead of it never moves and the transcript shows the tool appearing
     * when it appeared, and never a rewritten history where it always existed.
     *
     * <p>The already-announced set derives from the conversation content itself, never
     * from a side field, so a persisted-and-restored conversation knows exactly what it
     * announced with no extra state to restore.
     */
    private void reconcileDeclaredTools() {
        Map<String, ToolDefinitionBlock> announced = announcedTools();
        List<ToolDefinitionBlock> pending = declaredTools.values().stream()
            .filter(declared -> !announced.containsKey(declared.name()))
            .map(DeclaredTool::definition)
            .toList();
        if (pending.isEmpty()) {
            return;
        }
        // A definition is its own DeclaredTool: replacing the source with what it produced
        // makes this the one and only conversion, whatever asks for the definition later
        for (ToolDefinitionBlock td : pending) {
            declaredTools.put(td.name(), td);
        }
        if (!frozen) {
            mainObjective.putIfAbsent(KEY_TOOL_WEIGHTS, new TextBlock("Available tools:\n" + TOOL_WEIGHT_GUIDANCE));
            for (ToolDefinitionBlock td : pending) {
                mainObjective.put(TOOL_KEY_PREFIX + td.name(), td);
            }
        }
        else {
            OutgoingMessage<Void> announcement = new OutgoingMessage<>(null);
            announcement.setRole("user");
            announcement.setTimestamp(Instant.now());
            announcement.addText("Additional tools now available:");
            for (ToolDefinitionBlock td : pending) {
                announcement.addToolDefinition(td);
            }
            messages.add(announcement);
        }
    }

    /**
     * The tool definitions this conversation has announced so far, by name, later
     * announcements winning: the initial palette in the objective, then anything
     * announced in the message stream. Derived on every call, never held as state.
     */
    public Map<String, ToolDefinitionBlock> announcedTools() {
        Map<String, ToolDefinitionBlock> announced = new LinkedHashMap<>();
        for (ContentBlock block : mainObjective.values()) {
            if (block instanceof ToolDefinitionBlock td) {
                announced.put(td.name(), td);
            }
        }
        for (Message message : messages) {
            if (message instanceof OutgoingMessage<?> outgoing) {
                for (ContentBlock block : outgoing.getContentBlocks()) {
                    if (block instanceof ToolDefinitionBlock td) {
                        announced.put(td.name(), td);
                    }
                }
            }
        }
        return announced;
    }

    /**
     * Puts text into the named objective slot; see {@link #putMainObjective(String, Object)}.
     */
    public void putMainObjective(String key, String text) {
        if (text != null && !text.isEmpty()) {
            putObjectiveBlock(key, new TextBlock(text));
        }
    }

    /**
     * Puts a POJO into the named objective slot. A key is a slot: the latest put wins,
     * in place, so calling this from anywhere - constructor, composer, a doer that is
     * not sure whether someone else already did - is always safe. A caller that wants
     * append semantics instead generates a fresh key per put.
     *
     * <p>Until this instance first renders, puts compose freely. After that the head is
     * frozen: a put whose value renders identically to what the slot holds is a silent
     * no-op (the sprinkle stays free forever), and anything else - a divergent value or
     * a new key - is refused as a loudly logged no-op, because it would silently
     * invalidate the cached prefix. A mid-conversation instruction change belongs in the
     * message stream, where the model consciously receives it.
     */
    public void putMainObjective(String key, Object pojo) {
        if (pojo != null) {
            putObjectiveBlock(key, new PojoBlock(pojo));
        }
    }

    /** True when the named objective slot holds a value. */
    public boolean hasMainObjective(String key) {
        return mainObjective.containsKey(key);
    }

    /**
     * Places a block into the objective map, enforcing the freeze; package-private so
     * the persistence restore path can replay any block type (including tool
     * definitions that lived in the head) under its persisted key.
     */
    void putObjectiveBlock(String key, ContentBlock block) {
        if (!frozen) {
            mainObjective.put(key, block);
            return;
        }
        ContentBlock existing = mainObjective.get(key);
        if (existing != null && renderedForm(existing).equals(renderedForm(block))) {
            return;
        }
        naughtyPervert("putMainObjective(\"" + key + "\")");
    }

    /**
     * Empties the objective map completely - framework slots included. Legal only
     * before the first render (the restore path nukes a fresh instance before
     * replaying persisted state); on a frozen head it is a loudly logged no-op.
     */
    public void nukeMainObjective() {
        if (frozen) {
            naughtyPervert("nukeMainObjective()");
            return;
        }
        mainObjective.clear();
    }

    /**
     * The freeze refusal: the head stays exactly as it is, the log carries the full
     * stack so the caller is identifiable, and the conversation proceeds. Deliberately
     * not an exception - a head-mutation bug must not take down a production flow over
     * content the model was never going to see change anyway.
     */
    private void naughtyPervert(String attempt) {
        log.error("You naughty pervert: {} after the first render. The head is frozen once it has been sent - mutating it would silently invalidate the cached prefix. The call was ignored; compose before the first render, or say it in the conversation flow as a message.",
                attempt);
        IllegalStateException mutation = new IllegalStateException("post-freeze head mutation: " + attempt);
        log.error(mutation.getMessage(), mutation);
    }

    /**
     * The canonical rendered form of an objective block, for freeze-time equality: two
     * blocks are the same value exactly when they render to the same text, because the
     * rendered text is what the provider cache keys on.
     */
    private static String renderedForm(ContentBlock block) {
        return switch (block) {
            case TextBlock tb -> tb.text();
            case PojoBlock pb -> NucleoJsonSerializer.writeSummarized(pb.pojo());
            case ToolDefinitionBlock td -> ai.redouble.nucleo.harness.llm.encode.ToolDefinitionBlockEncoder.renderText(td);
            default -> ContentBlocks.toJsonNode(block).toString();
        };
    }

    /**
     * Gets the main objective as a flat copy of its values, in render order.
     *
     * @return list of content blocks in the main objective
     */
    public List<ContentBlock> getMainObjectiveBlocks() {
        return new ArrayList<>(mainObjective.values());
    }

    /** The objective map itself - named slots in render order - as an ordered copy. */
    public Map<String, ContentBlock> getMainObjective() {
        return new LinkedHashMap<>(mainObjective);
    }

    /**
     * Every declared tool as its wire definition, in declaration order. Already-rendered
     * declarations return their existing definition; a declared-but-never-rendered tool
     * converts here - this is the "right before serialization" conversion point, so a
     * persisted conversation never loses a declaration with the live object it was
     * declared through.
     */
    public List<ToolDefinitionBlock> declaredToolDefinitions() {
        List<ToolDefinitionBlock> definitions = new ArrayList<>();
        for (DeclaredTool declared : declaredTools.values()) {
            definitions.add(declared.definition());
        }
        return definitions;
    }

    public boolean isCacheMainObjective() {
        return cacheMainObjective;
    }

    public void setCacheMainObjective(boolean cacheMainObjective) {
        this.cacheMainObjective = cacheMainObjective;
    }

    /**
     * Admits a Skill into this conversation's preamble. Per-provider LLM clients pull
     * {@link #getLoadedSkills()} at each request and emit each skill at a
     * provider-appropriate position (Anthropic: cacheable system block; OpenAI: prepended
     * {@code role:system} message; Bedrock: extra {@code SystemContentBlock}).
     */
    public void addSkill(ai.redouble.nucleo.prompt.skill.Skill skill) {
        if (skill == null) {
            return;
        }
        for (ai.redouble.nucleo.prompt.skill.Skill existing : loadedSkills) {
            if (existing.name().equals(skill.name())) {
                return;
            }
        }
        loadedSkills.add(skill);
    }

    public List<ai.redouble.nucleo.prompt.skill.Skill> getLoadedSkills() {
        return new ArrayList<>(loadedSkills);
    }

    public void clearLoadedSkills() {
        loadedSkills.clear();
    }

    public Depth getDepth() {
        return depth;
    }

    public void setDepth(Depth depth) {
        this.depth = depth;
    }

    public OutputDeclaration getOutputDeclaration() {
        return outputDeclaration;
    }

    public void setOutputDeclaration(OutputDeclaration outputDeclaration) {
        this.outputDeclaration = outputDeclaration;
    }

    /**
     * Resolves depth once: the conversation's own declaration, else the wired binding's
     * (a job that attaches its resolved binding declared the depth there). Nothing
     * declaring anywhere is uncorrectable: the seat never said how hard to think.
     * Kill switch and model capability are applied downstream, not here.
     */
    public Depth resolveDepth() {
        if (depth != null) {
            return depth;
        }
        if (modelBinding != null && modelBinding.getDepth() != null) {
            return modelBinding.getDepth();
        }
        throw new UncorrectableRuntimeLLMException("Conversation " + conversationId + " declares no depth"
                + describeObjective() + ". A thinker's conversation is stamped by ConversationService; a job that"
                + " builds its own conversation sets the depth it declared in its model requirement, or attaches"
                + " that binding with setModelBinding.");
    }

    /**
     * Reasoning tokens booked for the next call on the resolved model at the resolved depth:
     * added to the wire output ceiling and to the local TPM reservation, so both sides of the
     * boundary agree. Zero when {@link ThinkingMode#reasoningReserved} says the call books none.
     */
    public int resolveThinkingBudget() {
        Depth d = resolveDepth();
        ModelSpec m = getModel();
        return ThinkingMode.reasoningReserved(m, d) ? m.getThinkingBudget(d) : 0;
    }

    /**
     * Output plus thinking for the next call, computed against a spec the caller names: the
     * compaction fit checks run before the next binding is resolved and account under the
     * prior spec, so they cannot go through {@link #getModel()}. Same declaration chain as
     * {@link #resolveOutputBudget()}, same clamp as the wire.
     */
    public int outputReserve(ModelSpec spec) {
        return outputReserve(spec, declaredOutputTokens(spec), resolveDepth());
    }

    /**
     * The reserve arithmetic in one place: output plus the spec's thinking budget when the
     * call books reasoning, clamped at the spec's output ceiling exactly as the Anthropic wire
     * clamps {@code max_tokens}. Used by the wired form through {@link #outputReserve(ModelSpec)}
     * and by the prompt and flat binding forms directly.
     */
    public static int outputReserve(ModelSpec spec, int outputTokens, Depth depth) {
        int thinking = ThinkingMode.reasoningReserved(spec, depth) ? spec.getThinkingBudget(depth) : 0;
        return Math.min(outputTokens + thinking, spec.getMaxOutputTokens());
    }

    public List<Message> getMessages() {
        return messages;
    }

    /**
     * Gets the artifact registry for this conversation.
     * The registry stores all artifacts encountered during the conversation
     * and survives message compaction.
     *
     * @return the artifact registry
     */
    public ArtifactRegistry getArtifactRegistry() {
        return artifactRegistry;
    }

    /**
     * Restores the artifact registry from a persisted state.
     * Clears the current registry and re-registers all artifacts.
     *
     * @param restoredRegistry the registry to restore from
     */
    public void setArtifactRegistry(ArtifactRegistry restoredRegistry) {
        artifactRegistry.clear();
        if (restoredRegistry != null) {
            for (Map.Entry<String, Artifact> entry : restoredRegistry.getAllArtifacts().entrySet()) {
                artifactRegistry.register(entry.getValue());
            }
        }
    }

    /**
     * Gets the last message in the conversation.
     *
     * @return the last message or null if no messages
     */
    public Message getLastMessage() {
        if (messages.isEmpty()) {
            return null;
        }
        return messages.get(messages.size() - 1);
    }

    /**
     * Gets the last outgoing message as an OutgoingMessage.
     * This is a convenience method that combines getLastMessage() with type checking.
     *
     * @return the last message as OutgoingMessage, or null if last message is not outgoing
     */
    @SuppressWarnings("unchecked")
    public <T> OutgoingMessage<T> getLastOutgoingMessage() {
        Message lastMessage = getLastMessage();
        if (lastMessage == null) {
            return null;
        }
        if (lastMessage instanceof OutgoingMessage) {
            return (OutgoingMessage<T>)lastMessage;
        }
        return null;
    }

    /**
     * Adds a message to the conversation.
     *
     * @param message the message to add
     */
    public void addMessage(Message message) {
        messages.add(message);
    }

    // Token counting methods for context window management

    /**
     * Gets total tokens counting against the context window.
     * Always estimates from scratch using the tokenizer for accuracy.
     *
     * <p>Use this method to determine if you're approaching context window limits.
     * This count represents what the LLM provider will see as your context size.
     *
     * @param model the model for estimation
     * @return total estimated tokens counting against context window
     */
    public int getTotalTokens(ModelSpec model) {
        if (model == null) {
            throw new IllegalArgumentException("Model is necessary to calculate tokens, cannot be null");
        }
        int total = 0;
        ContentProcessor processor = createContentProcessor();
        TokenCounter counter = TokenizerFactory.get().forModel(model);

        // Count main objective blocks (always sent, regardless of cache setting)
        for (ContentBlock block : mainObjective.values()) {
            total += countBlockTokens(block, processor, counter);
        }

        // Estimate all messages
        for (Message message : messages) {
            total += message.getEstimatedTokens(model);
        }

        // Always add artifact registry estimate - it's appended during prepareMessagesForLLM()
        // and can grow between API calls as new artifacts are discovered
        String registrySection = processor.buildArtifactRegistrySection();
        if (!registrySection.isEmpty()) {
            total += counter.countTokens(registrySection);
        }
        return total;
    }

    /**
     * Resolves the output token budget for the next LLM call on this conversation.
     * Single source of truth consumed by both the job reservation and the API payload
     * (the {@code max_tokens} field). Both numbers must stay in lock-step - Anthropic and
     * Bedrock pre-debit max_tokens from their upstream TPM bucket at request time, so a
     * reservation smaller than max_tokens silently overflows the provider.
     *
     * <p>Resolution order, three declaration layers and no default:
     * <ol>
     *   <li>{@code lastOutgoingMessage.getRequestedOutputTokens()} - the per-call override
     *       (a tool sizing one call, or the truncation escalation)</li>
     *   <li>this conversation's {@link #getOutputDeclaration() declaration} - the thinker's
     *       rung or count, stamped by ConversationService</li>
     *   <li>the wired binding's declaration - a job that attached its resolved binding
     *       declared the output in its model requirement</li>
     * </ol>
     * Nothing declaring anywhere is uncorrectable: the seat never said how much it answers.
     * The result is capped at {@link ModelSpec#getMaxOutputTokens()} here rather than in each
     * client, because a budget above what the model can emit is not a budget: the provider
     * rejects the request outright (Bedrock answers a 400 naming the ceiling), and the
     * reservation would meter tokens the call could never produce.
     */
    public int resolveOutputBudget() {
        ModelSpec spec = getModel();
        return Math.min(declaredOutputTokens(spec), spec.getMaxOutputTokens());
    }

    private int declaredOutputTokens(ModelSpec spec) {
        OutgoingMessage<?> last = getLastOutgoingMessage();
        Integer requested = last != null ? last.getRequestedOutputTokens() : null;
        if (requested != null) {
            return requested;
        }
        if (outputDeclaration != null) {
            return outputDeclaration.tokens(spec);
        }
        if (modelBinding != null && modelBinding.getOutput() != null) {
            return modelBinding.getOutput().tokens(spec);
        }
        throw new UncorrectableRuntimeLLMException("Conversation " + conversationId + " declares no output size"
                + describeObjective() + ". A thinker declares setOutputSize (or setOutputBudget for a seat that fits"
                + " no rung) and ConversationService stamps it here; a job that builds its own conversation declares"
                + " the output on it or attaches the binding it declared the output on.");
    }

    private String describeObjective() {
        if (mainObjective.isEmpty()) {
            return "";
        }
        return " (objective keys " + mainObjective.keySet() + ")";
    }

    /**
     * The inputs beyond text the outgoing messages carry, anywhere in the history: an image
     * block is {@link Input#IMAGES}, a file block {@link Input#DOCUMENTS}. The payload fact the
     * resolution gate checks against what the request declared and what the resolved entry
     * accepts; it never chooses the model, the declaration does.
     */
    public Set<Input> carriedInputs() {
        Set<Input> carried = EnumSet.noneOf(Input.class);
        for (Message message : messages) {
            if (message instanceof OutgoingMessage<?> out) {
                for (ContentBlock block : out.getContentBlocks()) {
                    if (block instanceof ImageBlock) {
                        carried.add(Input.IMAGES);
                    }
                    else if (block instanceof FileBlock) {
                        carried.add(Input.DOCUMENTS);
                    }
                }
            }
        }
        return carried;
    }

    private ContentProcessor createContentProcessor() {
        return summarizer != null
            ? new ContentProcessor(this, summarizer)
            : new ContentProcessor(this);
    }

    /**
     * Counts tokens for a single content block, delegating to the ContentProcessor for
     * PojoBlocks so they see exactly the serialized form that will reach the LLM.
     */
    private int countBlockTokens(ContentBlock block, ContentProcessor processor, TokenCounter counter) {
        if (block instanceof TextBlock(String text)) {
            return counter.countTokens(text);
        }
        if (block instanceof PojoBlock pb) {
            ContentBlock processed = processor.processForLLM(pb);
            if (processed instanceof JsonBlock(String json)) {
                return counter.countTokens(json);
            }
            return 0;
        }
        if (block instanceof JsonBlock(String json)) {
            return counter.countTokens(json);
        }
        if (block instanceof ToolDefinitionBlock td) {
            // the counted string IS the text-fallback wire form, one authority for both
            return counter.countTokens(ai.redouble.nucleo.harness.llm.encode.ToolDefinitionBlockEncoder.renderText(td));
        }
        return 0;
    }

    /**
     * Prepares all conversation messages for LLM consumption.
     *
     * <p>This method is the mandatory pipeline for processing conversation content
     * before sending to an LLM. It ensures that:
     * <ul>
     *   <li>Artifacts in POJOs are replaced with @ref placeholders</li>
     *   <li>Content is properly formatted</li>
     *   <li>Response instructions are appended where needed</li>
     *   <li>The artifact registry is appended at the end</li>
     * </ul>
     *
     * <p>LLM clients MUST use this method instead of directly accessing messages
     * to ensure consistent artifact processing.
     *
     * <p>The main objective is the conversation's system content - its ONLY system
     * content - and comes back as {@link PreparedConversation#systemText()}, rendered
     * through the same pipeline as everything else. It is never a turn, so clients
     * need no convention to tell it apart from the conversation and nothing tempts
     * them to re-derive routing from the raw context. Turns are user or assistant,
     * nothing else; a message carrying any other role is refused here, at the single
     * point where roles become wire roles.
     *
     * @param formatter the content formatter for the target LLM dialect
     * @param thinkingActive true if a native thinking block is guaranteed for this call, in which
     *                       case the handler omits the envelope's prose reasoning field from the schema
     * @return the prepared conversation: system content and turns, ready for LLM encoding
     */
    public PreparedConversation prepareMessagesForLLM(ContentFormatter formatter, boolean thinkingActive) {
        // The framework's voice lands now: the legend if this request renders an @-notation
        // contract, declared-but-unseen tools converting to definitions - in the head if this
        // is the first render, in the stream if the head is already frozen - then the freeze
        // takes effect: from here on the head is immutable and all change is flow
        reconcileLegend();
        reconcileDeclaredTools();
        frozen = true;
        ContentProcessor processor = createContentProcessor();
        List<ContentBlock> objectiveBlocks = new ArrayList<>(mainObjective.values());
        String systemText = objectiveBlocks.isEmpty()
            ? ""
            : processor.buildTextContent(objectiveBlocks, formatter);
        // The initial palette lives in the objective but travels as its own channel, typed:
        // the client alone decides whether it becomes text after the system content or a
        // native tools parameter
        List<ToolDefinitionBlock> palette = objectiveBlocks.stream()
            .filter(ToolDefinitionBlock.class::isInstance)
            .map(ToolDefinitionBlock.class::cast)
            .toList();
        List<ProcessedMessageData> result = new ArrayList<>();
        // Process each conversation message
        for (Message message : messages) {
            if (message instanceof OutgoingMessage<?> outgoing) {
                // Get content blocks and process them through artifact replacement
                List<ContentBlock> blocks = outgoing.getContentBlocks();
                List<ContentBlock> processedBlocks;
                if (!blocks.isEmpty()) {
                    // Process blocks to replace artifacts with refs
                    processedBlocks = new ArrayList<>(processor.processForLLM(blocks));
                }
                else {
                    // No blocks, create from raw content
                    String rawContent = message.getRawContent();
                    processedBlocks = (rawContent != null && !rawContent.isEmpty())
                        ? new ArrayList<>(List.of(new TextBlock(rawContent)))
                        : new ArrayList<>();
                }
                // Append response instructions as a TextBlock if present
                ResponseHandler<?> handler = outgoing.getResponseHandler();
                if (handler != null) {
                    String instructions = handler.responseInstructions(formatter.supportsNativeToolCalling(), thinkingActive);
                    if (instructions != null && !instructions.isEmpty()) {
                        processedBlocks.add(new TextBlock(instructions));
                    }
                }
                result.add(new ProcessedMessageData(TurnRole.of(message.getRole()), processedBlocks, message.isEnableCache()));
            }
            else if (message instanceof IncomingMessage<?> incoming) {
                // Use content blocks directly (already in correct format)
                List<ContentBlock> blocks = (incoming.getContentBlocks() != null && !incoming.getContentBlocks().isEmpty())
                    ? incoming.getContentBlocks()
                    : List.of(new TextBlock(incoming.getRawContent()));
                result.add(new ProcessedMessageData(TurnRole.of(message.getRole()), blocks, message.isEnableCache()));
            }
            else {
                // Other message types - pass through without processing
                result.add(ProcessedMessageData.textOnly(TurnRole.of(message.getRole()), message.getRawContent(), message.isEnableCache()));
            }
        }
        // Append artifact registry at the end if not empty
        String registrySection = processor.buildArtifactRegistrySection();
        if (!registrySection.isEmpty()) {
            // The registry is data derived from tool results, not instruction, so it is a
            // user turn - never system content
            result.add(ProcessedMessageData.textOnly(TurnRole.USER, registrySection, false));
        }
        return new PreparedConversation(systemText, cacheMainObjective, palette, result);
    }

    /**
     * Creates a persistence snapshot of this conversation.
     *
     * @return a snapshot suitable for persistence
     */
    public ConversationPersistenceSnapshot toSnapshot() {
        return ConversationPersistenceSnapshot.fromConversation(this);
    }

    /**
     * Restores a conversation from a persistence snapshot. The stored serving-spec id
     * comes back as the prior; the restored conversation is unbound until a job wires a
     * fresh binding.
     *
     * @param snapshot        the snapshot to restore from
     * @param responseHandler the response handler for message rehydration
     * @return the restored conversation context
     */
    public static ConversationContext fromSnapshot(ConversationPersistenceSnapshot snapshot, ResponseHandler<?> responseHandler) {
        return snapshot.toConversation(responseHandler);
    }
}