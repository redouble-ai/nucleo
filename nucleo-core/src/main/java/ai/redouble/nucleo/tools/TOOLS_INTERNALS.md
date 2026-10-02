# Inside tools, thinkers and doers

This page is for people working on the runtime itself: the type hierarchy, the model-seat and
one-call tool mechanics, the benchmark's internals, the enforcement of the read-only binding,
the thinker loop turn by turn, the answer-shape boundary, the reconcile hooks, and the
built-in orchestrators behind `sub_thinker`, `request_tools` and `request_skill`. The guide
for writing tools, thinkers and doers is [Tools, thinkers and doers](PACKAGE.md).

---

## Type Hierarchy

```
Tool<I,O> (interface)
    │
    └─► Orchestrator<I,O> (interface) ─── resource-free marker
            │
            ├─► Doer<I,O> (interface) ─── coded orchestration
            │
            └─► Thinker<I,O> (interface) ─ LLM-driven orchestration
```

**Tool**  -  base interface. Accepts typed input, produces typed output. Can be invoked by name.

**Orchestrator**  -  a Tool that holds no resources. It coordinates other jobs but doesn't do work itself.

**Doer**  -  an Orchestrator with coded logic. You write Java code that spawns jobs, waits on results, branches, loops.

**Thinker**  -  an Orchestrator with an LLM loop. The LLM decides which tools to call next.

### Abstract Classes

```
AbstractJob<O>
    │
    ├─► AbstractTool<I,O> ─────────────── holds resources during execution
    │
    └─► AbstractOrchestrator<I,O> ─────── holds NOTHING
            │
            ├─► AbstractDoer<I,O> ─────── coded orchestration
            │
            └─► AbstractThinker<I,O> ──── LLM conversation loop
                    │
                    ├─► SingleObjectiveThinker<I,O> ── goal-directed agent
                    │       └─► SubThinker ──────────── runtime delegate at the parent's grade
                    ├─► ReactiveThinker ────────────── chat turn over a durable conversation
                    │       └─► ChatThinker ─────────── the durable-chat seat (non-temporary conversation, streamed answers)
                    └─► AbstractToollessThinker<I,O> ── one call, no loop, the output POJO's own schema
```

`AbstractOrchestrator` extends `AbstractTool`: its `getRequirements()` is final and answers
null, its `execute(JobResources, JobContext)` is final and bridges to the abstract
`execute(JobContext)`, and its `getTimeout()` is final and answers null.

### Quick Reference

| Class | Resources | Duration | Decides |
|-------|-----------|----------|---------|
| `AbstractTool` | Holds during execution | Seconds | N/A (single operation) |
| `AbstractDoer` | Holds NOTHING | Hours/Days | Your Java code |
| `AbstractThinker` | Holds NOTHING | Minutes | LLM |
| `SingleObjectiveThinker` | Holds NOTHING | Minutes | LLM toward goal |
| `ReactiveThinker` | Holds NOTHING | One turn (drains its inbox, then completes) | LLM per message |
| `AbstractToollessThinker` | Holds NOTHING | One call per candidate answer | LLM once, no tools |

---

## Model-dependent jobs: `ModelDependent`

Everything that takes a seat at a model implements `ModelDependent`: a grade
(`getGrade`/`setGrade`), an optional pin (`pinModel`/`pinnedModel`), and `requireSeat(req,
depth)`, which asks the requirements for the pinned entry when there is one and for the
grade otherwise. `Thinker`, `LLMCall` and every one-call tool are `ModelDependent`;
`ModelDependentTool<I,O>` is the union with `Tool<I,O>`, the type a benchmark races. A
thinker's pin reaches every `LLMCall` it makes, so pinning a thinker pins its whole
conversation to one entry. A pin bypasses the picker, never the compliance envelope.

## One-call tools: `AbstractModelDependentTool`, wire, converse, unwrap

A tool whose work is one exchange with a model extends `AbstractModelDependentTool<I,O>`,
declares its grade in the constructor (`super(parent, Grade.SMALL)`), and holds no client
and no model. In `getRequirements` it calls `wireConversation(req, depth, output,
OutputType.class, instructions)`, which builds the conversation once (the input rendered as
the prompt, the response contract from the output POJO) and wires a fresh binding onto it
per attempt through `requireSeat`, so the reservation is priced from exactly what the wire
sends and a pinned instance resolves to its entry. A tool whose conversation the prompt
cannot express - one carrying images or files - builds it itself and calls
`wireConversation(req, depth, this::build)` instead: built once through the supplier, given a
fresh binding per attempt the same way, and conversed through the same `converse`, so its
answer is corrected the same way; a tool that sends its own conversation on the client
loses that. In `execute` it calls
`converse(resources)`, which sends the conversation on the client resolved for the binding
and returns the typed answer, correcting the model the way `LLMCall` does (the two share
`ResponseCorrection.exchange`): an answer that does not parse, or parses without its
required fields, goes back as a correction turn and the dispatcher re-runs the same
instance on the grown conversation, up to `ResponseCorrection.MAX_CORRECTIONS` times; past
that a parse failure surfaces and an invalid answer is returned as it is. The tool wraps the
exchange in `catch (Exception e) { throw LLMReadableCheckedException.unwrap(e); }`, and
`unwrap` lets the dispatcher's retry signals through (upstream throttling, a correction, a
truncation escalation), so the broad catch never turns the model's second chance into a
failure. `client(resources)` is the one seam a test overrides to answer without a
transport. `QuickLLMQuestionTool` is the shape; `AbstractToolCorrectionTest` pins the
protocol. `AbstractTool` itself stays the base for tools that call no model at all.

A one-call tool that declares no grade of its own inherits its parent's when
`ClassToolProvider` creates it. `SummarizationTool` and `AnalyzeViolationTool` declare
`Grade.SMALL` in their constructors; `QuickLLMQuestionTool` takes its grade from its input.

## Benchmark: any model-dependent job, raced across its grade

`Benchmark<I,O>` (`tools.benchmark`) is a doer over a factory of any
`ModelDependentTool<I,O>`, taking the job's own input. It runs the job once unpinned (the
reference: what the deployment's picker serves for the grade), then `runs` times on every
open, callable entry of the grade (`Benchmark.candidates(grade)`, or an explicit list),
each pinned to its entry and identical otherwise, all submitted at once under admission.
The reference runs first and alone: its full run - the thinker's `transcript()`, every tool
it called and what the tool returned, and its answer - is the ground truth. Then `JudgeTool`,
at `Grade.CEILING`, compares each candidate run in full against it in a call of its own,
submitted the moment the run lands so the judges overlap the race, under one fixed rubric
(7 points accuracy with a mis-used tool costing at least 3, 3 points shown work), blind: no
model is named on either side, so a judge cannot favour its own family, and the reference
itself is not judged. A `Scorer<O>` adds a score in code where the output is comparable to
the reference's without a model. The benchmark returns the reference's output, so a
workflow that wraps a step in one gets what it would have got unwrapped, and the
`BenchmarkReport` goes sideways: one row per run (model, calls, iterations, tokens,
latency, wall time, cost in the entry's currency, judge score and reason, scorer score),
means per model, logged as a table and published on the job's metadata under
`Benchmark.REPORT`; `report()` reads it after the run. The numbers come from a private
`CostLedger` fed by the dispatcher for the benchmark's workflow, attributed to runs by job
lineage, so a thinker's calls and its children's calls land on its row. A run that fails
is a row that says so; a reference that fails fails the benchmark after the table is
logged and every run unjudged; a judge that fails costs its own run's score, with the
reason on the row, and the report names a judge failure only when every judge failed.
`BenchmarkTest` drives all of it through the dispatcher on a job that answers with the
model it was served.

## Service tools: the three outcomes

A tool that calls an external service with a parameter the model supplied wraps that one
step, not the whole body, in `LLMReadableCheckedException.wrapWithContext`. It keeps the
correctable/uncorrectable distinction and adds what the model needs: a correctable failure
from the client (a 400, a 404 on a fetch) comes back as an `InvalidInputException` naming the
parameter as the model knows it and its value, so the model knows which of its own inputs to
fix; an uncorrectable one, or a raw `IOException`, comes back as an `ExternalServiceException`
naming the service and the step, so no failure of the model's own parameter is ever reported
as `SystemException("internal", ...)`. Retry signals pass through it exactly as through
`unwrap`. The tool's own validation throws (a missing parameter, a status it classifies
itself) stay outside the wrap, and the outer
`catch (Exception e) { throw LLMReadableCheckedException.unwrap(e); }` stays for whatever
else the body can throw. `EPOClaimsTool`, `PubMedFetchTool` and `WebFetchTool` are the shape;
`ErrorsHierarchyContractTest` pins the three outcomes.

## The read-only flag at runtime

`ToolProvider.readOnly()` carries the flag at runtime. It defaults to `false`, which is the
correct answer rather than a fallback: an MCP server describes no such property, and
`request_tools` admits arbitrary tools, so neither can be established as read-only. A
provider that wraps a class-backed one must forward the flag, or the description the model
reads and the contract admission enforces drift apart.

## Enforcing the read-only binding

Enforcement is two points, and only the second is a guarantee:

| Point | Does | Why not sufficient alone |
|---|---|---|
| `buildToolDefinitionBlocks` -> `ReadOnlyPalette.sweep` | Withholds mutating tools from the definitions the LLM sees | The registry is rebuilt every turn and can be widened after the definitions were built |
| `submitToolCall` -> `ReadOnlyPalette.requireReadOnly` | Refuses a call to any tool that is not read-only, as a correctable `GuardrailException` | - |

Both sit in `AbstractThinker`'s own call path, deliberately outside the overridable hooks.
The sweep runs after `reconcileToolRegistry()` returns, so no ordering between overrides
matters, and the admission check sits beside `validateToolCall` rather than inside it, so a
subclass that overrides validation without chaining `super` cannot drop the guarantee.

Withheld tools are logged, never dropped silently: a palette that quietly shrank is
indistinguishable from one that was never populated, which matters when a skeptic reports
it could not verify something. Filtering applies even to tools the class itself declared,
since the binding can be applied by a subclass constructor over an inherited declaration.

`sub_thinker` and `request_tools` need no special case: neither is marked read-only, so the
sweep withholds them like anything else.

Both constraints travel across a delegation, by the same mechanism: the submission door
seals the spawning orchestrator's `ScopeGuard` onto every child it submits, and the guard
carries both the flow's identity axes and its `ReadOnlyScope` binding. A sub-agent of a
ticket-bound read-only thinker is bound to the same ticket AND read-only itself, and so are
its own descendants - no class arranges it. That is what makes the binding hold at the
agent-as-tool edge too, where a thinker marked `readOnly = true` in its annotation would
otherwise run unbound and be free to widen its own palette.

## Step ordinals: recording call order

Every child a doer spawns is stamped with its position in the doer's execution order, seeded
into the child's context metadata under `AbstractOrchestrator.META_ITERATION` (`"iteration"`)
for a deployment's recorder to persist with the job's row and use to reconstruct the
trajectory. The runtime itself writes only the metadata. The contract:

- **Sequential calls are numbered by call order.** A doer that calls A, then B, then C must stamp
  A=1, B=2, C=3. `submitInStep(job)` does this: it bumps the doer's step counter and stamps the new
  ordinal, so back-to-back submits get 1, 2, 3...
- **The same ordinal under one parent means GENUINELY parallel.** Two children share a parent and an
  ordinal only when they truly run at the same time. To fan out: call `nextStep()` once to
  claim an ordinal, then `submitInCurrentStep(job)` for every parallel job - they all share that one
  ordinal.
- **Never bypass these helpers.** A raw `JobDispatcher.getInstance().submit()` leaves the
  ordinal unset, so the child has no place in the trajectory. Use `submitInStep` /
  `submitInCurrentStep` for every child a doer spawns. (For a thinker, the framework stamps each
  agentic turn's LLM call and the tools it requests with that turn's number - same idea, automatic.)

The trajectory is a TREE, not a flat list. Where a recorder persists each job with its parent
and this ordinal, reconstruct it by walking the parent links depth-first and ordering each
parent's children by (ordinal, start time). A flat sort by the ordinal (it is per-parent, so it
collides across parents) or by start time (parallel branches interleave) looks like noise even
when every ordinal is correct.

## What a thinker declares, in full

A seat speaks three words - "MEDIUM model, STANDARD effort,
COMPACT answer" - and the numbers behind them belong to the catalog entry, never to the
seat and never to a company-wide constant. There is no default for any of them, silent or
otherwise. The grade and the output size travel in a `ThinkerDeclaration`, the object
every thinker base constructor requires, so a thinker without them does not compile; the
declaration also carries the optional comfort window and compaction trigger, and a
future seat-level word joins it there without touching any constructor signature.
`ConversationService` stamps all three words onto the conversation at obtain time so the
first call and the compaction fit check read them before any per-call code runs.

- **Grade** (`ThinkerDeclaration.grade`): the capability floor the work needs, a rung of
  the ladder or `Grade.CEILING`. CEILING is not a rung: it names the strongest grade the
  deployment serves, and the picker gate resolves it to that rung
  (`ModelPickers.resolveWith`, through `ModelPicker.ceiling()`) before any picker sees
  the seat - MEGA where the deployment pins one, XL where it does not. A chat that wants the
  strongest model the deployment serves declares CEILING; a chat that wants a fixed rung
  declares that rung instead. A `SubThinker` always works at its parent's rung, and a
  one-call tool that declares no grade of its own inherits its parent's when
  `ClassToolProvider` creates it. A caller may raise a thinker's grade for a particular run
  after construction. The one tool whose grade is INPUT rather than a seat declaration is
  `QuickLLMQuestionTool`: it is how anyone, code or model, asks one question without paying
  for a thinker, so the asker states the rung per question in `QuickLLMQuestionInput.grade`
  (required).
- **Depth**: the effort. A goal-directed thinker's depth is its input's
  (`ThinkerInput.depth`, STANDARD unless the caller says otherwise, clamped to the parent's);
  a reactive thinker has no input, so it carries its own field, STANDARD unless its
  constructor calls `setDepth`. The catalog entry translates depth into reasoning tokens.
- **Output size** (`ThinkerDeclaration.output`, a rung or a raw count): how much answer the
  turns book, as the per-turn `max_tokens` and the TPM reservation. One rung per seat,
  sized to its LARGEST turn, because a loop turn may be a tool call or the final answer
  and the declaration covers both. Read the output POJO and pick the rung by its shape:
  `VERDICT` a label, a boolean, a classification, a one-field POJO; `COMPACT` a POJO with
  a handful of fields, a short list, a tool call with its arguments; `STANDARD` a rich
  POJO, a list of records, a table - the typical thinker final answer; `EXTENDED` a written
  section, a report, a long extraction list; `MAX` as much as the model can emit. The
  catalog entry translates the rung (framework table VERDICT 1,024 / COMPACT 4,096 /
  STANDARD 16,384 / EXTENDED 32,768, each capped at the entry's ceiling, MAX the ceiling
  itself; an entry may declare its own `output_budgets`). `setOutputBudget(int)` is the
  documented exception for a seat whose answer fits no rung; the last declaration set
  wins, the same refinement the grade allows. Budgets never propagate: a `SubThinker`
  declares its own rung sized to `SubThinkerOutput`, because the parent's was sized to
  the parent's answer. `OutputSize.STANDARD` and `Depth.STANDARD` share a name on purpose;
  neither enum is ever statically imported, so every site reads which STANDARD it sets.
- **Comfort window and compaction trigger** (`setComfortContextTokens`,
  `setCompactionTrigger`): optional overrides of the framework's 128K comfort window and
  0.92 trigger; see [Conversations](../harness/conversation/PACKAGE.md).

**Sizing from history.** The deployment's persisted record says what each seat really
produced: where a host records its LLM runs, the output tokens of a job class give the
class's maximum and 95th percentile, and the smallest rung above the maximum is the
declaration. A `MAX_TOKENS` stop reason on a class afterwards is the correction signal:
that seat's rung moves up. Between the two sits the truncation escalation - one re-run at
the model ceiling for a seat that guessed one rung low - which is the safety net, never
the plan.

## The thinker loop

1. Thinker puts its task into the objective (`putMainObjective("task", ...)`) and declares
   its tools (`addTools`). The framework guidance derives at render from what the
   conversation carries: the `@`-notation legend follows the response contract (the
   thinker's envelope handler declares `usesSchemaNotation()`), and the tool-weight
   guidance renders when declared tools first do - the thinker composes only what it
   authored. The objective is the conversation's system content; see
   [Conversations](../harness/conversation/PACKAGE.md) for the channel contract and the
   objective-map slots.
2. LLM client encodes tools for the provider (native API or text-based)
3. LLM returns tool calls or final answer
4. If tool calls: execute all, batch results into one message, repeat
5. If final answer: resolve its artifact refs, then run the thinker's declared
   validation guards (`declareValidationGuardrails()`) against it while the conversation
   is still held. A refusal becomes a correction turn and the loop repeats; the
   accepted answer is streamed and returned. See [Scope and the trust
   boundary](guardrails/PACKAGE.md). The LLM turn itself is one protected seam,
   `submitThinkingCall`, which is how a test drives the real loop with scripted turns.

Providers with native tool calling (Anthropic, OpenAI's dialect) send tools via their tools parameter and receive `ToolUseBlock`s with provider-assigned IDs. Other providers fall back to text-based tool descriptions and JSON tool calls, with ids the runtime mints. The thinker is unaware of which path is used - it always gets a `ThinkingResponse`.

### The answer is a boundary

A model is a third party and its output is untrusted, so `ThinkingResponseHandler` judges
the shape of what comes back before anything typed is handed on. It performs exactly one
conversion, a JSON object into a composite (POJO) answer type; an answer the declared type
cannot hold is refused as a correctable `InvalidInputException` naming the `answer`
parameter and the type the schema declared, never the value the model sent. So a composite
answer type refuses anything that is not an object - a flattened string being the common
case - and a leaf answer type such as the `String` a `ReactiveThinker` declares refuses an
object, an array, and a scalar of another kind. The refusal is correctable because the only
useful reaction is to re-ask: a wrong shape passed on instead reaches the loop's erased
cast, where it becomes a `ClassCastException` inside a `SystemException` that ends the run
and tells the model nothing. Whether the correction is actually put to the model depends on
the seat: the single-objective loop appends it and re-asks, while a reactive exchange ends
on it. A null or absent answer is no answer yet, not a wrong shape.

A right-shaped answer whose declared field is mistyped fails inside the decoder instead,
and that refusal too is composed from the schema rather than from the decoder's complaint:
every deserializer the framework registers carries the declared type it was feeding, so the
refusal names an enum's accepted values or the declared type's name, never the value the
model sent. The decoder's own complaint, which does quote the value, survives on the
retained cause, where a stack trace may carry it and prose never does.
`ThinkingResponseHandlerAnswerShapeTest` pins both the composition and the echo-freedom by
canary.

When a native thinking block is guaranteed for the call (`ThinkingMode.thinkingActive(model, effort)`), the `ThinkingResponse` schema omits its prose `reasoning` field: the model reasons in the thinking block and `ThinkingResponseHandler.resolveReasoning()` populates the envelope reasoning from that block, so asking for a JSON reasoning field would be redundant output that gets discarded. The strip reaches the typed answer POJO's own nested `reasoning` too (a `ReasonablePojo` answer carries one), for the same reason: under native thinking the answer's reasoning is not asked for, and without native thinking it stays, as the reasoning callers receive. Separately from the strip, the descriptions of every reasoning field ask for a justification a reader can check, never for the model's thought process, in any wording (`ThinkingResponseHandlerStripTest`): a reasoning-extraction classifier keys on that wording whether or not a thinking block is in play. Opus 5.5 refused a field described as the model's "thought process" on every call without thinking and on half of them with it, and passed the same field reworded, and the `ChainOfThoughtReasoning` shape (approach and steps), on every call either way.

## Reconcile Hooks

`AbstractThinker` exposes protected hooks that run at the start of every LLM turn (inside `buildToolDefinitionBlocks` and right before the catalog enum is rebuilt for `request_tools`). The default implementations handle framework concerns; subclasses override to add their own logic and may call `super` to keep the defaults.

| Hook | Authoritative over | Default behavior |
|------|--------------------|------------------|
| `reconcileToolRegistry()` | Tool registry contents (prompt-side `ToolDefinitionBlock`s and dispatcher lookup share this state) | Registers `SubThinker` when depth ≥ `delegationThreshold()` (STANDARD by default), unregisters otherwise. Registers `RequestToolsProvider` with the current reconciled tool catalog when non-empty, unregisters otherwise; the same for `RequestSkillProvider` and the skill catalog. |
| `reconcileCatalog(Set<ToolProvider>)` | The ToolHub compatible-tools catalog before it reaches either the `request_tools` schema or the admission lookup | Returns the input unchanged. |
| `reconcileSkillCatalog(Set<Skill>)` | The ToolHub compatible-skills catalog before it reaches either the `request_skill` schema or the admission lookup | Returns the input unchanged. |

The hooks must be idempotent (state-based, not action-based): the same input state called twice must produce the same registry contents. `ToolRegistry.register` and `unregister` are no-ops when the registry is already in the desired state.

### Depth

`ThinkerInput.depth` defaults to `STANDARD` and is never null. It propagates parent → child via a `Depth.min` ceiling - a child may dial down but never exceed the parent's depth. The LLM at each level reads depth as a semantic hint for how hard to work (and is told in the field description to never raise it beyond what was given). A reactive thinker has no input and carries its own depth field (STANDARD unless its constructor says otherwise). `Thinker.getDepth()` is the one source per shape; `ConversationService` stamps it onto the conversation, and the reconcile hook reads it, so a depth change on the input is reflected at the next reconcile.

## Built-in orchestrators

### SubThinker

Dynamic sub-agent registered automatically for all thinkers (when the thinker's depth ≥ STANDARD - the default `reconcileToolRegistry` hides it at QUICK/IMMEDIATE so the LLM cannot delegate). The parent's LLM provides a natural language objective and the refs of the artifacts it conveys, and the sub-agent inherits all parent tools and works at the parent's grade. Typed artifacts flow back through the standard registry pipeline.

**How it works:**
- `AbstractThinker` constructor registers `SubThinker.class`; the default `reconcileToolRegistry` hook keeps or removes it each turn based on the thinker's own depth against `delegationThreshold()`
- The `SubThinker` constructor inherits the parent thinker's full tool list (the provider instances, so dynamically-wired tools survive; the two catalog doors are not copied - they are per-turn snapshots the delegate rebuilds) and captures the parent's reconciled `request_tools` and `request_skill` catalogs, so its own doors offer exactly what the parent's offered at launch
- The spawning thinker's sealed `ScopeGuard` is copied onto the delegate by the submission door, so a delegate of a scoped flow carries the same binding and cannot drift out of it (`GuardrailEnforcementTest` pins both the copy and the refusal)
- The delegate's registry is seeded with `ArtifactRegistry.filter(refs)` over the refs its input lists in `artifactRefs`; a ref mentioned only in the objective's text is not conveyed
- Returns `SubThinkerOutput`: text summary + structured `AnalysisReasoning` + artifact refs
- Parent receives resolved typed artifacts via `NucleoJsonSerializer.writeSummarizedWithRefs()`

**Depth behavior:**

| Sub-agent's own depth | Behavior |
|-----------------------|----------|
| IMMEDIATE, QUICK | Runs at the requested effort; recursive sub-agents disabled |
| STANDARD | Runs normally; recursive sub-agents disabled |
| THOROUGH, ULTRA_THOROUGH | Runs at high effort; recursive sub-agents allowed |

The parent always sees `sub_thinker` in its palette when its own depth ≥ STANDARD. Whether a spawned `SubThinker` can itself spawn further sub-agents is decided by `SubThinker`'s `delegationThreshold()`, which raises the bar to THOROUGH.

### RequestToolsProvider

Dynamic `ToolProvider` for `request_tools`. Built per turn from the reconciled compatible-tools catalog: the JSON schema enumerates current tool names as a `oneOf` array of `{const, description}` entries inside the `tool_names` field, spelled in snake_case like every class-backed schema because that is the spelling the serializer reads back. The LLM provider validates names natively against the enum, so an unrecognized name cannot reach admission. The compatible catalog never enters the system prompt as free text.

### RequestSkillProvider

Dynamic `ToolProvider` for `request_skill`, the skill counterpart of `request_tools`. Built per turn from the thinker's reconciled skill catalog (`declareCompatibleSkills()` resolved by `ToolHub`, then `reconcileSkillCatalog`): the schema enumerates the catalog's skill names as `oneOf` `{const, description}` entries inside `skill_names`, each description being the skill's own. A call goes to `ToolHub.requestSkills`, which runs the skill admission rules (`registerSkillAdmission`) and attaches each admitted skill to the conversation the thinker handed the tool through `ConversationAware`; the skill's text rides the preamble of every later call. Read-only by declaration, so a read-only thinker keeps it where `request_tools` is swept.
