# Inside the trust boundary

This page is for people working on the runtime itself: where the scope and the guardrails are
enforced, the rules of the submission door, and the contract every guardrail is evaluated
under. The guide for using them is [Scope and the trust boundary](PACKAGE.md), and writing
each guardrail kind is [Writing guardrails](../../guardrails/PACKAGE.md).

## Where the pieces live

The sealed kind hierarchy, the scope vocabulary, the reference guardrails and the
declaration interfaces live in `ai.redouble.nucleo.guardrails`; the enforcement engine
(`GuardrailEnforcer`) and the submission door (`JobDispatcher`) live in
`ai.redouble.nucleo.harness`. This package holds the concrete content checks
(`SizeCapGuardrail`, `SchemaConformanceGuardrail`, `UrlGuardrail`,
`ExfiltrationMarkerGuardrail`).

The hierarchy is sealed accordingly: `Guardrail` permits exactly `AuthGuardrail`, `ContentGuardrail`, `AdmissionGuardrail`, `ValidationGuardrail` (all in `ai.redouble.nucleo.guardrails`). Wrong-seat declarations do not compile - the typed returns of the declare methods are the seat rules.

Reference implementations ship in Nucleo, one per rung, doubling as the enforcement test fixtures: `PayloadSizeCapGuardrail` (content INPUT, parameterized), `PIIDetectionGuardrail` (content OUTPUT), `PrincipalAllowListGuardrail` (auth), `AgentClassAllowListAdmissionGuardrail` (admission), plus `TenantScope` + `TenantScoped` as the reference scope axis.

## Enforcement: the framework, on every route

Nothing in application or orchestration code runs guardrails. `GuardrailEnforcer` (in `ai.redouble.nucleo.harness`), called from the dispatch path, enforces them for every job on every route - thinker-driven, doer-direct, fan-out worker, delegated sub-agent, and whatever transport arrives later. Protection is a property of the tool and the flow, never of the route a call took.

- **Input side** runs after dependency resolution and before resource allocation (the dispatcher thread holds nothing there): declared admission guards, declared INPUT-direction content guards, declared auth guards. A refusal fails the gated job before any resource is spent.
- **Output side** runs after resources are released and before the result is delivered: declared OUTPUT-direction content guards against the result. A refusal converts the attempt into a failure; the caller never sees the result.
- Guards run once per submission; the dispatcher's transparent retries do not re-run them on an unchanged input.
- Applicability is `targetType().isInstance(target)` - self-reported by every guardrail, no reflection.
- A guard failing with anything other than `GuardrailException` is an infrastructure failure: the gated job fails closed with that exception as-is, never laundered into a refusal.
- Guard jobs are submitted through the dispatcher's internal door on the gated job's behalf, and that door runs the scope wall against the guard the gated job was admitted under: a `Scoped` guard whose claim drifts from the flow is refused before it runs, and a guard that declares `ScopeAuthority` is sealed with the flow so a judge it spawns inherits the binding. A refusal at that door is not a verdict - the guard never ran - and surfaces as a `SystemException` of the gated job carrying the refusal: a guard whose own declaration disagrees with the flow it guards is a code error, never a policy decision. A guard's `scope()` is its own claim, never derived from its target.
- Zero-guard executions are auditable: the enforcer writes applied-guard counts to `obs.guards_input` / `obs.guards_output` / `obs.guards_validation` on every guarded job's context.
- Guardrail jobs are not themselves guarded - enforcement cannot recurse.

### The evaluation contract

A guardrail is a function of exactly two things: `validate(target)` with the target set before submission, and the immutable `JobSnapshot` of the gated job (`setGatedSnapshot`). No live framework object ever crosses into a guardrail: never the Tool, never the JobContext, never an orchestrator. Everything else is the guardrail's own lookup.

All refusals are `GuardrailException` - correctable by design, so the agent loop can act on the message. The message is the whole semantics: never new exception classes. Capability-wide "never" belongs to admission, where refusal means the tool is not even offered.

## Scope enforcement: the submission door

The `*Scoped` pattern is an identity statement. The examples' `CaseAgent` carrying a `customerId` does not merely "have access to" a customer - the instance IS customer C-100's case. The scope machinery's single job is keeping the flow inside its own identity: every id the model writes into a tool input must equal the id the code assigned.

The same `*Scoped` marker plays two roles on the two sides of the trust boundary. On an input it carries the CLAIM: written by the model, every call - and the field the door judges is the same field the tool acts on, so there is no gap between what was checked and what was executed. On an orchestrator it IS the binding - implementing the marker is the whole declaration.

Enforcement is the submission door in `JobDispatcher.dispatch`, on every route:

- Only orchestrators may submit from inside a job. A plain job or a rogue thread spawned inside `execute()` is refused outright - there is no unguarded seat to submit from.
- At an orchestrator's own dispatch, the door composes the caller's sealed guard with the orchestrator's `scope()` and seals the effective `ScopeGuard` onto it - the establishment act, before it executes. After the seal there is no mutation surface: "once set, the scope cannot be changed by model output" holds structurally.
- At every submission out of that orchestrator, the sealed guard judges the child's own scope and the child input's claim (`Scoped.scope()`), axis by axis for composites. Descendants inherit by merge: delegation (SubThinker) and fan-out workers are covered automatically, and an inner authority cannot shed an outer one's scope.
- The guard carries the flow's read-only binding as well (`ReadOnlyScope`), so it inherits by the same merge: a delegate or an agent-as-tool of a read-only flow is read-only itself, transitively, and withholds the mutating tools it inherited. What a read-only binding MEANS is enforced by the palette (`ReadOnlyPalette`); riding the guard is what makes it travel.
- A refusal is a born-failed handle: `get()` throws `ExecutionException` caused by the `GuardrailException`, identical in shape to every other guardrail refusal.

A sub-flow with a genuinely different scope is expressed the only way it can be: trusted code constructing and dispatching a new scoped orchestrator with a new binding, bounded by the auth rung. Permission checks do not belong in scope judgments: "may this principal touch this entity at all" is an `AuthGuardrail` seated on the tools (`PrincipalAllowListGuardrail` is the reference shape).

### Authoring a new scope axis

1. A record implementing `Scope` (the examples' `record CustomerScope(String customerId) implements Scope`).
2. A marker extending `Scoped` with the id getter and a default `scope()` returning the record (`CustomerScoped.getCustomerId()`).
3. Every input that carries the claim implements the marker; the orchestrator that owns the flow implements the same marker.

Nothing else: no guardrail class, no registration, no per-tool wiring - any submission in the flow's subtree carrying the marker is judged.

An axis that refines another (a line item within an invoice) is an inheritance chain, because this is Java: the scope becomes a plain immutable class extending the parent scope (records cannot extend), and the marker extends the parent marker overriding `scope()`. Each level's `matches` adds its own fields on top of `super.matches`, guarding its own cast with `instanceof` so it skips itself for a candidate that does not reach its depth. `Scope.pass` then judges any two related claims on the axes both carry - an invoice-bound guard pins a line-item claim's invoice axis, a line-item-bound guard pins a plain invoice claim, and two sibling refinements pin each other's shared parent axis - with no extra machinery (the region scope in `ScopeValueTest`, refined by a city scope that is itself refined by a block scope and by a sibling district scope, is the worked example). `CompositeScope` is only for a carrier claiming several UNRELATED axes at once.

## What the runtime records about a refusal

A guardrail is a job: the enforcer submits it and blocks on the handle, so a refusal travels the ordinary job lifecycle. The agent sees it - `GuardrailException` is correctable, so the loop hands the message to the LLM as an observation it can act on. Observers see it - the guard job's failure reaches the message bus like any other, which is how a deployment forwards refusals to a SIEM or audit table (see [OBSERVABILITY.md](../../harness/observability/OBSERVABILITY.md)); routing policy is the deployment's observer, not framework behavior. The runtime writes the gating relationship onto the guard job's metadata - `AbstractGuardrail.OBS_GATES_JOB_ID` names the gated job, `OBS_GATES_PHASE` the rung (`AUTH` / `ADMISSION` / `CONTENT_INPUT` / `CONTENT_OUTPUT` / `VALIDATION`) - so a deployment's recorder can lift them into columns of its own and query guardrail rows against the jobs they gated. Scope refusals travel the same exception surface but spawn no guardrail job: the door refuses before the child exists.

## The validation seat

An OUTPUT content guard on a thinker can only refuse delivery: it runs at the dispatch door after the thinker's `execute()` has returned, and the thinker released its conversation before that. Whoever called the thinker gets the failure, and the model that wrote the answer, the one holding every fact needed to fix it, never hears about it. A validation guard runs at the other seat: the final-answer branch of a goal-directed thinker (`SingleObjectiveThinker`, and `AbstractToollessThinker`), after artifact refs are resolved and before the answer is streamed, while the conversation is still held.

- The thinker declares its validation guards through `declareValidationGuardrails()` (declared on `AbstractThinker`, none by default), typed to its answer, and they are consulted once per candidate.
- A refusal becomes a correction turn in the thinker's own conversation, carrying the guard's message, and the candidate is dropped. The model answers again with its full context. In `SingleObjectiveThinker` each refusal spends one iteration, so `maxIterations` bounds the retries; a thinker that runs out returns a null answer, exactly as when it never reaches one. `AbstractToollessThinker` allows `MAX_GUARDRAIL_CORRECTIONS` (two) correction turns and then fails with the refusal.
- Anything else a validation guard fails with propagates as itself and fails the thinker closed, and a validation guard refused at the internal door for a drifting scope claim fails the thinker closed too, without a correction turn: a guard that never ran has nothing to tell the model.
- What the guard does to reach its verdict is its author's business - a coded field-by-field check against the record, a model call under the guard's own declared resources, or a judge thinker the guard spawns when it declares `ScopeAuthority`. A guard that waits on children declares no resources, and its timeout is whatever its author sets.
- Content OUTPUT guards and validation guards ask different questions - "may this leave" versus "is this right" - so a thinker may declare both, and nothing runs twice: validation at the seat, content OUTPUT at the door on the answer that passed.

The use case that shaped it: a thinker runs a long flow and produces a record; a guard checks every field against what it knows; on a discrepancy the thinker, not its caller, is told what is wrong and fixes it with everything it already has in context.

## Type hierarchy

```
Guardrail<T> (sealed, Nucleo) -- validate(T), setTarget, targetType(), setGatedSnapshot
    |
    +-> AuthGuardrail<T>                             seat: tools
    +-> ContentGuardrail<T>   (+ direction())        seat: tools (INPUT and OUTPUT)
    +-> AdmissionGuardrail    (+ init(AdmissionContext))  seat: tools + ToolHub rules
    +-> ValidationGuardrail<T>                       seat: the goal-directed thinker's final answer

AbstractGuardrail<T> (Nucleo)          -- job plumbing; implements no branch; carries the
    |                                     scope-guard seat a guard uses only when it declares ScopeAuthority
    +-> AbstractContentGuardrail<T>    (Nucleo)
    +-> AbstractAuthGuardrail<T>       (Nucleo)
    +-> AbstractAdmissionGuardrail     (Nucleo)
    +-> AbstractValidationGuardrail<T> (Nucleo)

Scope / Scoped / CompositeScope / ScopeGuard (nucleo.guardrails) -- the scope vocabulary,
judged at the submission door, guard jobs included, no guardrail jobs spawned for it
```

Declaration seats: `GuardedExecution` (implemented by every `Tool`) carries the three tool-side declare methods; `AbstractThinker` carries `declareValidationGuardrails()`, consulted by `SingleObjectiveThinker` and `AbstractToollessThinker`; `ScopeAuthority` (extended by `Orchestrator`, declarable by a guardrail) carries the scope-guard field and marks the only classes allowed to submit from inside a job.

## Thread safety

| Component | Thread Safety |
|-----------|---------------|
| `GuardrailEnforcer` | Thread-safe (stateless entry points) |
| `ScopeGuard` | Immutable |
| Guardrail instances | Single-threaded (one instance per validation) |
