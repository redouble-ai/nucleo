# Inside conversations

This page is for people working on the runtime itself: the channel contract, the objective
map and its freeze, the transformation pipeline, token accounting, persistence and the
ownership machinery of `ConversationService`. The guide for using conversations is
[Conversations](PACKAGE.md).

---

## Tool System

Tools are declared to a conversation as `DeclaredTool`s and render as `ToolDefinitionBlock`
records:

```java
public record ToolDefinitionBlock(
    String name,           // "search_documents"
    String description,    // "Search for relevant documents..."
    String schemaJson      // JSON Schema from @LLMRequired/@LLMDescription
) implements ContentBlock, DeclaredTool {
    // a definition is its own DeclaredTool: definition() returns this
}
```

### Registration

```java
context.addTools(thinker.buildToolDefinitionBlocks());
```

Declarations are add-only: a name already declared is a no-op, whatever the new
declaration would produce, so per-round refreshes cost nothing and a definition is never
redefined or removed. Conversion to the wire definition happens at the first render after
the declaration, never eagerly (see "The channel contract" below for where the definition
lands).

### How LLMs See Tools

How tools reach the model depends on the provider's `ContentFormatter.supportsNativeToolCalling()`:

**Native tool calling** (Anthropic, OpenAI's dialect): the client sweeps `ToolDefinitionBlock`s from the prepared turns into the provider's tools parameter, and its own encoder suppresses the inline form. The `ThinkingResponse` schema excludes `tool_calls` - the model uses `ToolUseBlock`s instead, under ids the provider minted.

**Text-based** (Bedrock Converse): `ToolDefinitionBlock`s render as text where they sit in the conversation, via the default `ToolDefinitionBlockEncoder`. The `ThinkingResponse` schema includes `tool_calls`, and a call parsed out of the text gets an id minted by the runtime from the tool's name and its place in the turn, so its result can be recorded against it.

### Schema stripping

`ResponseHandler.responseInstructions(nativeToolsAvailable, thinkingActive)` omits fields the model should not fill:

- `tool_calls` is dropped when native tool calling is available (the model uses native `ToolUseBlock`s).
- `reasoning` is dropped when `thinkingActive` is true - a native thinking block is guaranteed for the call, so the envelope's prose reasoning would be redundant output the model is asked to write and `ThinkingResponseHandler.resolveReasoning()` then discards. The typed answer POJO's own nested `reasoning` is dropped with it: asking a natively thinking model to narrate its thinking again is what a reasoning-extraction classifier refuses (see [tools/PACKAGE.md](../../tools/PACKAGE.md)).

`ConversationContext.prepareMessagesForLLM(formatter, thinkingActive)` passes both signals to the response handler, keeping the thinker unaware of the encoding. The `thinkingActive` flag is computed by the client from its own model via `ThinkingMode.thinkingActive(model, depth)` - the same predicate that gates the request-time thinking config - so the schema strip and the request agree on whether a thinking block is coming back.

### The channel contract

`prepareMessagesForLLM` returns a `PreparedConversation`: the system content and the turns,
separately. That distinction is the whole contract, made once, here:

- **The main objective is the conversation's ONLY system content.** It comes back as
  `PreparedConversation.systemText()`, rendered through the same pipeline as everything
  else. A provider with a system channel puts it there (Anthropic's `system` parameter,
  Converse's `system` blocks); a provider without one expresses it as that provider
  carries instructions (OpenAI: a leading `role:system` message). Clients render what
  they are handed and decide nothing about routing: a second routing decision inside a
  client can only agree with this one or contradict it, and a contradiction means
  duplicated or dropped content with no error anywhere.
- **Turns are `TurnRole.USER` or `TurnRole.ASSISTANT`, nothing else.** The enum has no
  system member, so system content is structurally incapable of being a turn. A message
  carrying any other role string is refused at the mapping (`TurnRole.of`), never
  silently relabelled. System content mid-conversation is forbidden: anything the model
  must read as system goes through `putMainObjective`, period.
- **Tools are declared, then reconciled at render.** `addTool` records a `DeclaredTool`
  by name - add-only, so declaring from anywhere is always safe and a name already
  declared is never redefined or removed. Conversion to a wire definition happens once,
  at the first render after the declaration, never eagerly: churn before that render is
  free. Where the definition lands depends on the freeze: at the conversation's first
  render the palette joins the head (under `tool:` keys, preceded by the tool-weight
  guidance, which appears exactly when tools do); after that the head is frozen and a
  newly declared tool is a stream message at the point of declaration, so the prefix
  ahead of it never moves and the transcript shows tools appearing when they appeared.
  The announced set derives from the conversation content itself, never a side field.
  How a definition reaches the provider is the client's dialect alone: the default
  encoder renders `ToolDefinitionBlockEncoder.renderText` inline; a client with a
  native tools API overrides its own encoder to suppress the inline form and sweeps the
  same blocks into its tools parameter - both halves in one class. Every token count of
  a definition measures `renderText`, so the estimate and the text-fallback wire cannot
  diverge.
- **The artifact registry is data, not instruction** - it derives from tool results - so
  it rides a trailing user turn, never system.
- **`cacheMainObjective` rides the prepared conversation** as `cacheSystem` and decides
  whether the system content is marked cacheable on providers with explicit cache
  control. The flag decides; the mere existence of system content does not.

### The objective map

The main objective is a `LinkedHashMap` of named slots; the values, in insertion order,
ARE the system content - keys never reach the wire. A key is a slot: `putMainObjective`
replaces its value in place (a re-put keeps the slot's original position, so a refresh
never reorders the rendered prefix), which makes putting idempotent and therefore safe
from anywhere - constructor, composer, a doer that is not sure whether someone else
already did. A caller that wants append semantics generates a fresh key per put.
`nukeMainObjective` empties the map completely, framework slots included, and nothing
re-seeds itself.

Two slots are the framework's own, and both are derived companions, not seeded state.
The schema-notation legend follows the response contract - the `@`-notation's only
producer (tool schemas are standard JSON Schema): at render, a conversation whose
outgoing message carries a handler with `usesSchemaNotation()` gets the legend under
`legend` - a thinker's or a bare `LLMCall`'s alike - and a plain-prose conversation
never carries it. The tool-weight guidance lands under `toolWeights` when declared
tools first render, and never for a tool-less conversation - a dangling "Available
tools:" header primes weak models. If the notation or a tool first appears after the
freeze, its companion arrives as a stream message at the point of appearance. Because
these slots derive from what the conversation renders, a nuke does not silence them: as
long as the contract or the declarations remain, the next render re-places them.

The head freezes at the instance's first render: after it, a put whose value renders
identically to the slot's is a silent no-op (refreshes stay free forever), and anything
else - a divergent value, a new key, a nuke - is a loudly logged no-op, stack trace
included, because it would silently invalidate the cached prefix. A mid-conversation
instruction change belongs in the message stream, where the model consciously receives
it. The freeze is instance-lifetime and never persists: a restored
conversation has not rendered, has no warm provider cache to lose, and composes freely
until its own first render - which is also how the framework slots of a restored
conversation self-heal to the current constants.

#### What belongs in the objective, and what does not

The main objective is standing instruction: the thinker's authored task, framework
guidance, the tool palette - and the app's CONFIGURED behavior, even when a person
typed that configuration into a settings form. A project's query prompt or an app's
scoped binding (the worked pattern in downstream chat thinkers: one slot, re-put on
every initialization) is the owner configuring how the assistant behaves, and its
authority must match that intent. Three properties decide the placement:

- **Authority matches intent.** Configuration governs the conversation. On a user
  turn it stands coequal with any chat message, and a user can argue the assistant
  out of it mid-conversation - the owner's configuration becomes decoration.
- **Structural non-leakage.** The objective is model input that no viewport,
  transcript replay, export, or future consumer of `messages` ever ships as
  conversation content. Instruction text on a user turn must be filtered by every
  present and future consumer, forever - and a consumer that forgets prints the
  app's internals (or its secrets) into the transcript.
- **Refresh semantics.** A restored conversation composes freely until its first
  render, so an objective slot refreshes against the CURRENT configuration on
  resume. A history turn is frozen at conversation creation - wrong semantics for
  configuration, right semantics only for what was actually said.

What stays out of the objective is DATA: corpus content, retrieved chunks, per-turn
runtime state - third-party text nobody curated. That rides user turns, where the
model weighs it as input rather than obeys it as authority, and it never churns the
cached prefix that every turn of every conversation pays for.

The line is curation, not the typing hands. Settings-form fields are curated by the
app's owner - the closest thing the deployment has to a system author. The
counter-scenario - a deployment where low-trust users edit those forms while others
chat - is real, but prompt-channel demotion is the wrong defense for it: prompt
placement was never the enforcement boundary in this architecture. Guardrails and
settings permissions hold that line; channels only calibrate emphasis.

### Schema Generation

Tool input schemas are generated from annotated POJOs:

```java
public class SearchInput  {
    @LLMRequired
    @LLMDescription("The search query")
    private String query;

    @LLMDescription("Maximum results to return (1-100)")
    private Integer maxResults;
}
```

Becomes:
```json
{
  "type": "object",
  "properties": {
    "query": {"type": "string", "description": "The search query"},
    "max_results": {"type": "integer", "description": "Maximum results to return (1-100)"}
  },
  "required": ["query"]
}
```

Property names are the fields' names in snake_case, unless a `@JsonProperty` names the
field otherwise; a `@JsonIgnore` field is left out.

An enum-typed field publishes `"type": "string"` with an `enum` array of the constants in
the form the serializer reads back (a collection of enums constrains its `items` the same
way), and `@LLMExample` values publish as the standard `examples` keyword, so a schema
consumer outside the process learns the choices without the `@`-notation legend. In the
notation the same field is `@type` `enum` with the constants under `@values`, which the
legend (`ConversationContext.SCHEMA_NOTATION_GUIDANCE`) explains.

---

## Prompt Caching (Anthropic)

Anthropic's prompt caching reduces costs by 90% for repeated context. Cache writes cost 125%, reads cost 10%.

### Cache Control

```java
context.setCacheMainObjective(true);  // Cache system prompt
message.setCache(true);                // Cache this message
```

### Breakpoint Limit

Anthropic allows **4 cache breakpoints per request**. `AnthropicSDKClient.buildMessageCreateParams`
enforces this. It first spends one breakpoint on the system prefix, when there are system
blocks (admitted skills, then the main objective), the backend supports caching and the
prepared conversation asks for it (`cacheSystem`). Then it walks the prepared turns in
order and anchors a breakpoint on the first `TextBlock` or `SkillBlock` of each turn marked
for caching, while breakpoints remain. Abridged from the client:

```java
int cacheBreakpointsUsed = 0;
final int MAX_CACHE_BREAKPOINTS = 4;

boolean cacheSystemPrefix = !systemBlocks.isEmpty() && isCachingSupported() && prepared.cacheSystem();
if (cacheSystemPrefix) {
    cacheBreakpointsUsed++;
}

for (ProcessedMessageData msg : prepared.turns()) {
    boolean shouldCache = isCachingSupported() && msg.cacheEnabled() && cacheBreakpointsUsed < MAX_CACHE_BREAKPOINTS;
    boolean appliedCache = false;
    for (ContentBlocks.ContentBlock block : msg.contentBlocks()) {
        ContentBlockParam param = encodeBlock(block);
        // ...
        if (shouldCache && !appliedCache && (block instanceof ContentBlocks.TextBlock || block instanceof ContentBlocks.SkillBlock)) {
            // rebuild this one block as a text param carrying cache_control (ephemeral)
            appliedCache = true;
            cacheBreakpointsUsed++;
        }
    }
}

// after the turns: when cacheSystemPrefix, the LAST system block is rebuilt with cache_control
```

Image, tool and thinking blocks never anchor a breakpoint. The system breakpoint is
counted before the turn loop, so turn anchors cannot take it, and it is applied to the last
system block, which covers the whole stable prefix: tool definitions, skills and the main
objective.

### Cache Metrics

```java
LLMResponse<T> response = client.singleResponse(request);

// Cache performance
response.getCacheCreationInputTokens();  // Tokens written (125% cost)
response.getCacheReadInputTokens();       // Tokens read (10% cost)
response.getCacheHitRate();               // 0.0-1.0
```

### What Gets Cached

| Content | Typical Strategy |
|---------|------------------|
| System prompt | Always cache (`cacheMainObjective=true`) |
| Tool definitions | Cached with system prompt |
| Document context | Cache if reused across turns |
| Recent messages | Usually don't cache (changing) |

---

## Content Blocks

Messages contain typed content blocks via a sealed interface:

```java
public sealed interface ContentBlock
    permits TextBlock, PojoBlock, JsonBlock, ToolDefinitionBlock,
            ImageBlock, FileBlock, ToolUseBlock, ToolResultBlock,
            ThinkingBlock, RedactedThinkingBlock, SkillBlock {}
```

| Block Type | Purpose | Serialization |
|------------|---------|---------------|
| `TextBlock` | Plain text | Direct |
| `PojoBlock` | Structured POJO objects | JSON via artifact processing |
| `JsonBlock` | Raw JSON | Provider-formatted |
| `ToolDefinitionBlock` | Tool schemas | Client dialect: native tools parameter, or rendered text |
| `ImageBlock` | Base64 images | Base64 + MIME type |
| `FileBlock` | Base64 files | Base64 + metadata |
| `ToolUseBlock` | LLM tool invocations | ID + name + input JSON |
| `ToolResultBlock` | Tool execution results | Tool ID + result + error flag |
| `ThinkingBlock` | Anthropic thinking | Text + opaque signature, echoed back VERBATIM on later turns (the provider 400s otherwise) |
| `RedactedThinkingBlock` | Policy-hidden thinking | Opaque data, replayed verbatim |
| `SkillBlock` | Admitted skill bundle | The skill lives on `ConversationContext.loadedSkills`; snapshot restores by registry name |

### Reading a conversation as text

`Transcript.render(conversation)` is the conversation for a reader other than its model: every
message under its role, in order - the text as said, each tool call with its name and
arguments, each tool result against the call it answers (an error result marked), an admitted
skill by name, an attachment by kind. Thinking blocks are left out (the provider's channel,
meant for nobody else) and so are tool schemas. A thinker keeps its last run's rendering as
`transcript()` after the conversation has gone back to the service; a benchmark hands it to
the judge, a person reads it as the trajectory (`TranscriptTest`).

### Transformation Pipeline

When preparing messages for LLM:

1. `PojoBlock` → `NucleoJsonSerializer.writeSummarizedWithRefs()` → artifacts replaced with `{"@ref": "..."}` → `JsonBlock`
2. `ToolDefinitionBlock` → travels typed on `PreparedConversation`; the client's dialect decides native-vs-text (see "The channel contract")
3. Other blocks → pass through

---

## Artifact Integration

Artifacts use dual channels: LLM sees data for reasoning, but data travels intact via registry.

### Serialization for LLM

`NucleoJsonSerializer.writeSummarizedWithRefs()` handles everything in one Jackson pass:
- Discovers `Artifact` fields, registers them in the registry, assigns references
- Replaces artifact fields with `{"@ref": "..."}`
- Summarizes `@LLMSummarizable` fields via the provided `Summarizer`

```java
Summarizer summarizer = new LLMSummarizer(this);
String json = NucleoJsonSerializer.writeSummarizedWithRefs(result, registry, summarizer);
// CitationArtifact fields become {"@ref": "«artifact:link:cite~a1b2c3»"}
// Long @LLMSummarizable fields become "[SUMMARY: 2847 chars] This comprehensive study..."
```

### Reference Format

```
«artifact:type~shortid»
Example: «artifact:link:cite~a1b2c3»
```

### Registry Dump

`ContentProcessor.buildArtifactRegistrySection()` appends all artifacts as the final user message, using `NucleoJsonSerializer.writeSummarized()` for each artifact:

```
=== ARTIFACT REGISTRY ===
Long text fields show summaries with original length. Use get_artifact_field(ref, fieldName) for full content.
Use search_artifact_content(query) to search across all artifact text.
```

LLM sees both the reference in context AND the summarized data in the registry. It reasons about data but can't corrupt it.

**Summary format**: `[SUMMARY: 2847 chars] This comprehensive study examines...`

The length prefix signals substantial content exists. LLM can call tools when it needs more detail.

**Reachable artifacts stay bounded**: the dump iterates `getAllArtifacts()`, which excludes the reachable tier - iterands of registered `ListArtifact`s, artifacts nested in registered artifacts' fields, and worker conveyances. A 200-iterand list contributes one digest entry (`iterand_type`, `count`, head sample), not 200 entries. Reachable artifacts remain resolvable by ref (`get()`) and searchable (`getAllArtifactsIncludingReachable()`); on persistence list iterands ride inside their list's full-form JSON and are rehydrated one by one via `ArtifactListDeserializer` (type from each iterand's own ref alias; an unresolvable iterand type fails the read rather than silently shortening the list).

---

## Token Tracking

### Fresh Estimation Approach

ConversationContext **always estimates tokens from scratch** using the tokenizer. This ensures accurate
context window calculations regardless of conversation history.

The objective and the artifact registry are counted on the strings `prepareMessagesForLLM`
would send - the objective's PojoBlocks via `ContentProcessor.processForLLM`, the registry via
`buildArtifactRegistrySection`. Messages are counted by the model's `TokenCounter` from their
raw content plus per-image/file arithmetic; that is an estimate of the rendered turn, and the
response instructions appended at render are not part of it - the compaction trigger's headroom
absorbs the difference. The first call runs the configured `Summarizer` (typically
`LLMSummarizer` set by the thinker) and populates the per-artifact summary cache; subsequent
calls reuse cached summaries.

### API-Reported Tokens Live on the Response

The provider's actual token counts belong to `LLMResponse` (billing, cache metrics,
observability), never to the conversation: each API call reports the ENTIRE
conversation's input, not an increment, so per-message input actuals cannot exist.
`getTotalTokens()` is the conversation's only token number.

### The seat's declarations

A conversation carries what its seat asked for, readable before any resolution: the
`Grade` (which model), the `Depth` (how much effort) and the `OutputDeclaration` (how much
answer: an `OutputSize` rung, or a raw token count for the seat that fits no rung).
`ConversationService` stamps all three from the thinker at every point it creates or
rehydrates a conversation for it - a rehydrated conversation takes the adopting thinker's
CURRENT declarations, not the ones it was stored with - and a job that builds its own
conversation either sets them or attaches the resolved binding it declared them on
(`setModelBinding`), which the resolution below reads as the last layer.

Two payload-derived facts sit beside the declarations: `carriedInputs()` reports the
inputs beyond text the outgoing messages carry anywhere in the history, an image block as
`Input.IMAGES` and a file block as `Input.DOCUMENTS`, which the resolution gate checks
against what the request declared and what the entry accepts (it never picks the model; the
declaration does); and
`ConversationContext.singleTurn(model, prompt, backstopTokens)` builds the one-turn
task-call form - the model given, one user message, thinking off (IMMEDIATE), the
answer capped at the backstop - built fresh per call so a truncation escalation never
leaks into the next one.

### Output Budget Resolution

`ConversationContext.resolveOutputBudget()` is the single source of truth for the
output-token budget of the next LLM call. Three declaration layers and no default:

1. The last outgoing message's `requestedOutputTokens` (the per-call override: a tool
   sizing one call, or the client's truncation escalation)
2. The conversation's `OutputDeclaration` (the thinker's rung or count), translated through
   the resolved entry (`ModelSpec.getOutputBudget(rung)`)
3. The wired binding's declaration (a job that attached its priced binding declared the
   output in its model requirement)

Nothing declaring anywhere throws `UncorrectableRuntimeLLMException`: the seat never said
how much it answers, and that is a bug to surface, never a number to invent. The same
chain resolves depth (`resolveDepth()`: the conversation's, else the binding's, else the
refusal).

The result is capped at `ModelSpec.getMaxOutputTokens()` here rather than in each client
because a budget above what the model can emit is not a budget: the provider rejects the
request outright (Bedrock answers a 400 naming the ceiling), and the reservation would
meter tokens the call could never produce.

`outputReserve(spec)` is the whole reserve: the resolved output plus the reasoning headroom
the call books (`ThinkingMode.reasoningReserved` times `spec.getThinkingBudget(depth)`), clamped
at the ceiling exactly as the Anthropic wire clamps `max_tokens`. It takes the spec as an
argument because the compaction fit checks run before the next binding is resolved and
account under the prior spec. Both sides of the boundary use it: the wired `ModelBinding`
adds it to the conversation's input token count when pricing the rate-limiter reservation
at resolution, and every SDK client's `resolveWireMaxTokens` sends the same number as the
upstream `max_tokens` (`max_completion_tokens` on OpenAI). Anthropic and Bedrock pre-debit
`max_tokens` from their own TPM bucket at request time, so local and upstream accounting
must agree - a local reservation smaller than `max_tokens` silently overflows the provider.

On truncation (normalized `LLMStopReason.MAX_TOKENS`, or output reaching the requested
ceiling as a backstop), the abstract client template bumps the SENT message's
`requestedOutputTokens` to `model.getMaxOutputTokens()` and throws
`OutputTruncationRetryException` carrying the previous and the new budget. The
dispatcher's retry loop re-enters `getRequirements()` for a one-shot retry: a job that
retains its conversation across attempts (a thinker's `LLMCall`, a one-call job that builds
its conversation once and re-wires a binding per attempt) reserves and sends the
escalated budget; a job that rebuilds its conversation per call re-runs at its declared
budget, and the dispatcher's second-truncation message says so by name (the model's own
ceiling refused the answer; the job re-issued its request without the escalation; picker
failover moved the attempt to a model with a lower ceiling).

That signal is raised only while a larger budget exists. Because the budget is already
capped at the ceiling, a call issued at the ceiling has nowhere to grow, and a retry would
spend a second identical upstream call to reach the same truncation. That case is the
model's own limit refusing the answer, so it surfaces as `UncorrectableRuntimeLLMException`
instead.

### Context Window

`ContextWindowManager` (in the `compaction/` subpackage) keeps a conversation inside two
limits - the comfort window and the compaction trigger - and walks the compaction ladder
when the trigger is crossed. The numbers, their three-level resolution, the ladder, and
the best-effort-vs-loud failure discipline are the subpackage's story:
[compaction/PACKAGE.md](compaction/PACKAGE.md). Only exhausting the hard context - the
model's `max_context_tokens` minus the seat's `outputReserve` - raises this package's
`ContextOverflowException`.

---

## Persistence & Restoration

### Snapshot Pattern

```
ConversationContext
    ↓ toSnapshot()
ConversationPersistenceSnapshot
    ↓ Jackson ObjectMapper
JSON String
    ↓
Database (SNAPSHOT_DATA column)
```

### Dual Stores

| Store | Purpose | TTL |
|-------|---------|-----|
| `SessionStore` | Fast, in-memory | Per-save (`saveWithTTL`) |
| `PersistentStore` | Database, durable | Permanent until archived |

ConversationService tries SessionStore first, falls back to PersistentStore. Both are
optional wiring (`setSessionStore` / `setPersistentStore`), and the framework ships no
implementation of either: the application supplies the stores it wires. Saves go to the
persistent store only.

Conversations are user-bound: every conversation-addressed store operation takes
the ACTING principal, and durable stores enforce ownership under that identity. There is
no synthetic system principal - callers always have a real user in hand and the contract
makes them hand it over. Planned-but-unbuilt store surface throws `NotImplementedException`
rather than pretending with a no-op or an empty result.

### What Survives JVM Restart

| Survives | Needs Rehydration |
|----------|-------------------|
| Message history | ResponseHandler (transient) |
| Artifact registry | ModelBinding (re-minted by the next job's requirements) |
| Main objective (keyed slots, in order) | Summarizer (reset by the owning thinker) |
| Declared tools (persisted as converted definitions) | |
| Seat declaration (grade, depth, interactive) + prior spec id | |
| Metadata (title, workflowId) | |

Message restoration drops any `tool_result` whose `tool_use` id appears nowhere in the
transcript: a provider refuses the WHOLE conversation over one such orphan, so keeping
it would make the stored conversation permanently unanswerable rather than merely
incomplete. Incoming messages persist their content blocks precisely so `tool_use` ids
survive for the results that follow them.

A restored conversation comes back UNBOUND: the snapshot stores the declaration and
the spec that last served it (as `priorSpecId`, available to the picker as
stickiness input), never a live binding. The next turn's job mints a fresh
`ModelBinding` and the harness resolves it - `getModel()` before that throws.

### Restoration Flow

```java
// 1. Service loads snapshot on behalf of the resuming thinker's user, with the
//    thinker as the read's caller: a store that reads through dispatched tools
//    parents them under it, so the load lands inside the workflow that needed it
//    instead of minting a root of its own. Callers without a live job (an endpoint
//    serving a request) pass a fresh workflow root instead.
ConversationPersistenceSnapshot snapshot = persistentStore.load(conversationId, userId, thinker);

// 2. Rehydrate with runtime components
ConversationContext context = snapshot.toConversation(responseHandler);

// 3. Thinker re-declares its current palette (add-only: only names the
//    conversation has never announced reach the model, as a stream message)
context.addTools(thinker.buildToolDefinitionBlocks());
```

Temporary conversations never reach this flow, on either side: the save discipline
skips them, and `obtainConversation` does not consult the stores at all for the
jobId-keyed default - the thinker asked for a fresh temporary conversation, so a
store consult can only miss, and on a durable store every miss is a dispatched read
paid by every lane thinker.

### Version History

PersistentStore declares time-travel; a store that has not built it throws
`NotImplementedException`:

```java
List<Instant> versions = store.getVersionHistory(conversationId, userId);
ConversationPersistenceSnapshot old = store.loadVersion(conversationId, versions.get(5), userId);
```

---

## ConversationService

Singleton service managing conversation ownership across agents.

### Conversation Identity

A thinker declares what its conversation IS through one of two intent-carrying setters,
and `obtainConversation`'s `mustExist` flag follows the declared intent:

```java
// Adopt: resume an existing conversation - obtaining FAILS if no store holds it
thinker.setConversationId(existingId);

// Mint: a brand-new durable conversation with an identity decoupled from job
// lineage, in the readable job-id shape with a conv marker
// (e.g. ChatThinker-conv-a26f-52b01fa3bd75)
String durableId = thinker.mintConversationId();

// Neither: the temporary default - the conversation is keyed by the thinker's own
// jobId and dies with its retention
```

Durable chat surfaces mint at creation and adopt on every later turn; a doer that
resumes a step adopts the prior thinker's jobId-keyed conversation and stays on the
temporary default.

### Exclusive Ownership

One job owns one conversation at a time. Each conversation's holder carries its own
lock and a `handedOff` condition: an acquiring turn parks on the condition until the
owner releases (or re-acquires at once when the conversation is free or already its
own), so a busy conversation is waited for, never spun on.

At acquisition success the holder also records the owning thinker and stamps the
context's workflowId with the acquiring turn's - warm holders, cold restores and
fresh creates all pass through the same point, so the context (and every snapshot
written from it) always names the turn currently driving it.

### Acquisition

```java
CompletableFuture<ConversationContext> future =
    ConversationService.getInstance().obtainConversation(
        conversationId, thinker, responseHandler, mustExist);

// Parks until the current owner releases, up to 30 seconds
// Fails the future with owner info on timeout
```

### The Owning Thinker

```java
// The live thinker driving a turn over this conversation, or null when idle.
// User-guarded: only the conversation's owner may reach it.
Thinker<?, ?> live = ConversationService.getInstance().owningThinker(conversationId, userId);
```

This is what lets a second socket join a running turn: check for a live
`ReactiveThinker`, offer the message to its inbox, and subscribe to its workflow.
A stale reference is harmless - a closed turn refuses the message and the caller
starts a fresh turn instead.

### The Save Discipline

Durability is the owner's job, at conversation boundaries, through
`saveConversation` - the one place that persists:

```java
// Fired on every user message in, every response out, and on an abnormal
// exchange end. The FIRST save of a minted conversation CREATES the durable
// record - titled and embedded from that first user message, exactly once
// (PersistentStore.createContext); every other save is a plain update with no
// reads (ContextStore.saveContext). A missing row on update means the user
// deleted the conversation mid-turn: the save fails loudly, never resurrects.
ConversationService.getInstance().saveConversation(context, thinker);
```

Nothing exists in the store before the first user message, and nothing else
saves: release transfers ownership and sets retention, eviction only frees
memory - both act on state the last boundary already persisted.

The first user message is `ConversationContext.firstUserUtterance()` - the first
message a HUMAN sent. A message the application composed on the user channel is
marked app-authored (`Message.setAppAuthored(true)`, riding `MessageSnapshot` so a
restored conversation keeps the distinction); the utterance scan skips it, so the
stored title, embedding, and objective all derive from what the user actually
asked. Standing app instructions belong in the main objective, not on user turns
(see the objective-map doctrine above) - but persisted history that predates that
placement carries preamble turns forever, and the chat viewport withholds
app-authored messages at the endpoint, so a client never receives them.

### Release Patterns

```java
// Explicit release: ownership + retention only, no persistence
ConversationService.getInstance().release(thinker);

// Safety net on job completion: releaseAllForJob(jobId) evicts only conversations
// the job failed to release itself - a thinker's own finally-release with retention
// has already run by then, so the warm holder survives normal turn completion

// Force release (user-initiated, security-checked): cancels the owning turn's
// whole WORKFLOW and lets that turn's own finally release the holder, so no second
// turn can acquire the context while the dying one still mutates it
ConversationService.getInstance().forceRelease(conversationId, userId);
```

### Multi-Thinker Handoff

```
Thinker A acquires conversation
Thinker A works, releases
Thinker B acquires same conversation (blocked until A releases)
Thinker B continues where A left off
```

### Periodic Cleanup

- Per-thinker retain-duration expiry (`Thinker.getRetainDuration()`)
- 5-minute cleanup interval
- Expired, unowned holders are evicted from memory (their state was saved at the
  last boundary; eviction never persists)

### Graceful Shutdown

ConversationService extends `AbstractStoppable` for coordinated shutdown. A request that
arrives while it is stopping is refused at the door of `obtainConversation`:

```java
if (isStopping()) {
    log.error("[ConvSvc] Shutting down, dropping request for {}", conversationId);
    return CompletableFuture.failedFuture(new IllegalStateException("ConversationService is shutting down"));
}
```

On stop: cleanup executor shuts down, all conversations cleared.

---

## Compaction

Long conversations exhaust context windows. Compaction summarizes older content - the
machinery (the ladder of levels, segments, age weighting, the best-effort-vs-loud
failure discipline, `CompactionJob`) lives in the `compaction/` subpackage:
[compaction/PACKAGE.md](compaction/PACKAGE.md).

This package's half of the contract is per-message compactability. Messages opt out of
compaction individually while the conversation remains compactable:

```java
// User messages should be preserved verbatim
userMessage.setCompactable(false);

// Final assistant answers should be preserved verbatim
assistantMessage.setCompactable(false);

// Tool results can be compacted (default is true)
toolResultMessage.setCompactable(true);  // This is the default
```

Chat conversations preserve the core dialogue (user inputs and assistant responses)
verbatim while verbose intermediate content (tool results, reasoning) compacts when
context limits are reached. The compactor respects `isCompactable()` at every level.
A whole conversation opts out via `ConversationContext.setCompactable(false)`.

When even maximum compaction cannot bring the call - input plus the seat's output
reserve - inside the model's hard context, the result is `ContextOverflowException`,
an `UncorrectableLLMException`: parent thinkers see the error and can adapt.

---

## Thread Safety

| Component | Thread Safety |
|-----------|---------------|
| `ConversationContext` | NOT thread-safe (single conversation flow) |
| `ConversationService` | Thread-safe (per-holder lock and condition) |
| Response handlers | Thread-safe (stateless after construction) |
| `ArtifactRegistry` | NOT thread-safe (per-conversation) |
