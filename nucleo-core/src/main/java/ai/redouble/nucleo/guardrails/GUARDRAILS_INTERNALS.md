# Inside guardrail enforcement

This page is for people working on the runtime itself: the plumbing every guardrail inherits,
the declaration interfaces, how each scope value judges, and the rules of the doors that
enforce them. The guide for writing guardrails is [Writing guardrails](PACKAGE.md), and the
concepts behind them are in [Scope and the trust boundary](../tools/guardrails/PACKAGE.md).
The enforcement engine (`GuardrailEnforcer`) and the submission door (`JobDispatcher`) live
in `ai.redouble.nucleo.harness`.

## The sealed hierarchy

`Guardrail<T>` is sealed to exactly four kinds - one per rung of the context ladder,
each consuming one more piece of framework context. Scope is deliberately NOT a guardrail
kind: a scope judgment happens at the submission door in `JobDispatcher`, against a value
the flow carries, with no job spawned - see the scope vocabulary below.

Common contract on `Guardrail`: `validate(T)` throwing `GuardrailException` (always
correctable), `setTarget`, `targetType()`
(self-reported applicability class - the framework never resolves it reflectively),
`setGatedSnapshot`. No live framework object ever crosses into a guardrail. A refusal
speaks to the model as `Guardrail violation: ` followed by the message the guard threw,
and keeps any cause it was given (`GuardrailContractTest`).

`AbstractGuardrail` is branch-free job plumbing (target/snapshot fields, resource
capture, observability metadata, final execute-calls-validate) plus the scope-guard
seat a guard needs only when it declares `ScopeAuthority` (see below). The per-branch
bases - `AbstractContentGuardrail`, `AbstractAuthGuardrail`, `AbstractAdmissionGuardrail`,
`AbstractValidationGuardrail` - add their branch interface and the `gates_phase` rung
label (`AUTH` / `ADMISSION` / `CONTENT_INPUT` / `CONTENT_OUTPUT` / `VALIDATION`).

The plumbing, pinned by `GuardrailContractTest`: a guardrail is a job of type
`GUARDRAIL` with a read-only requirement and a ten-second timeout unless its author
sets another; `execute` is final and calls `validate` on the target set before
submission, returning nothing; before it runs it records the target's serialized form
under `obs.input` (an admission guard records `principal via caller -> Tool` there
instead), the gated job's id under `obs.gates_job_id` and the rung under
`obs.gates_phase`, and after it runs `obs.output` is `FAIL` when `validate` threw and
`PASS` otherwise, the guard's own record since the context never carries the failure; the gated
snapshot, and so an auth guard's principal, is null when the guard runs outside dispatch
enforcement (the Prompts facade gates no job); `setScopeGuard` refuses with
`UnsupportedOperationException` while `sealScopeGuard` installs what the door composed.
An admission guard validates a `Void` target, so `validate` is `checkAdmission`; its
`AdmissionContext` carries a null `callerClassName` for a call directly under the
workflow root. `GuardedExecution` declares no guardrails of any kind by default.

## Declaration interfaces

- `GuardedExecution` - implemented by every `Tool`: `guardedInputTarget()` plus the
  three tool-side declare methods. Content and auth declarations are consulted per
  invocation after the input is set and return constructed single-use instances
  (parameterized guards are first-class). Admission declarations are contractually
  input-free - they are also consulted by `ToolHub` when a thinker requests the tool
  through `request_tools`, where no input exists. `ToolHub` reads them from a throwaway
  instance of the tool it constructs for the purpose, and runs its own registered
  admission rules beside them; the rules registered on `ToolHub` run only there, while a
  tool's declared admission guards run again at dispatch, which is the guarantee.
- Validation declarations live on the producer, not on `GuardedExecution`: a leaf
  cannot act on a verdict about its own output, a thinker can. Both goal-directed
  thinkers, `SingleObjectiveThinker` and `AbstractToollessThinker`, consult
  `declareValidationGuardrails()` (declared on `AbstractThinker`) at their final-answer
  seat; it returns instances typed to the thinker's answer, consulted once per candidate.
- `ScopeAuthority` - extended by `Orchestrator`: the scope-guard seat
  (`getScopeGuard` / `setScopeGuard` / `sealScopeGuard`) and the submission-authority
  marker the door's caller rule checks. Leaf tools and plain jobs structurally lack it.
  A guardrail may declare it: `AbstractGuardrail` already carries the seat, so the
  declaration is the whole opt-in. The internal door then seals the guard from the
  gated flow, and children the guard submits - a judge thinker, say - inherit that
  binding like any child. A guard never authors a scope: its `setScopeGuard` always
  refuses with `UnsupportedOperationException`, where an orchestrator's refuses with
  `IllegalStateException` only once sealed. A guard that waits on children declares no
  resources, the rule every blocking job obeys. Its timeout is its own: the ten-second
  default holds unless the author sets another.

## The scope vocabulary

- `Scope` - a value judging membership in a flow's binding. `pass(Scope candidate)`
  recognizes related types through Java inheritance - parent vs child, child vs
  parent, siblings under one parent - whenever the two class chains share a
  `Scope`-implementing class, and compares exactly the axes both sides carry via
  `matches` (default: value equality); a candidate of an unrelated type passes
  silently, which is what lets independent axes coexist. A refining axis extends its
  parent scope class (an application's line-item scope extending its invoice scope;
  the reactor's own example is the region scope in `ScopeValueTest`, refined by a city
  scope and a sibling district scope, the city refined in turn by a block scope); its
  `matches` guards
  its own cast with `instanceof` and skips itself for a candidate that does not
  reach its depth, so either side of a related pair judges correctly. An axis
  overrides `pass` only for genuinely non-equality semantics. The refusal reads
  `Scope mismatch: this flow is bound to <binding> but the input names <claim>`. A
  single scope's `pass` sees a composite candidate as an unrelated type; it is
  `ScopeGuard.passAll` that flattens a composite so each axis meets the members that
  recognize it (`ScopeValueTest`).
- `Scoped` - the carrier contract: `scope()` produces the value. Axis markers
  (`TenantScoped` here, an application's own markers beside it) extend it with an id
  getter and a default
  `scope()`, so implementing the marker is the whole declaration - on an input it is the
  claim, on an orchestrator it is the flow's binding, on a guardrail it is the guard's
  own claim, judged at the internal door before the guard runs.
- `CompositeScope` - a flattening value for carriers claiming several UNRELATED axes
  at once (related axes are an inheritance chain, not a composite); as a candidate it
  is judged axis by axis, as a guard member it judges each component. Two composites
  are equal when their component lists are equal in order, so a guard dedups them by
  value; it prints as `CompositeScope` followed by its components.
- `ReadOnlyScope` - the read-only binding, carried as a scope so it inherits and seals
  like any other. It judges no candidate; a bound thinker's palette is what enforces it
  (`ReadOnlyPalette`). Riding the guard is what makes the binding unforgeable and
  transitive across delegates and agents-as-tools. Value-equal by type, so two
  read-only bindings merge into one member.
- `ScopeGuard` - an immutable, equality-deduped bag of scopes, built from varargs or a
  list; `passAll(Scope)` is AND over members; `merge` returns a new guard. A field on every orchestrator
  (`AbstractOrchestrator`), sealed at dispatch: the door composes the caller's guard
  with the orchestrator's own scope and captures the effective guard frozen. After the
  seal, `setScopeGuard` throws and `getScopeGuard` returns the captured value -
  "once set, the scope cannot be changed by model output" holds by absence of a
  mutation surface.

`TenantScope` + `TenantScoped` are the reference scope axis - the complete recipe in
two files: a record implementing `Scope`, a marker extending `Scoped` with the id
getter and a default `scope()` that produces the record from the getter.

## The submission door

Enforcement is the submission door in `JobDispatcher.dispatch`: only orchestrators may
submit from inside a job; the caller's sealed guard judges the child's own scope and
the child input's claim; descendants inherit the guard by merge. A refusal is a
born-failed handle whose `get()` throws `ExecutionException` caused by the
`GuardrailException` - the same surface as every other guardrail refusal. The door's
other rules are refusals as `SystemException`, never scope verdicts: a plain job or a
thread spawned inside `execute()` may not submit; a submission must arrive on the
thread of its live parent, so nothing from outside can inject a child under a running
job; an orchestrator whose `scope()` throws is a refused submission carrying that
failure, never an unscoped escape; a delayed submission fires on a fresh thread and so
creates workflow roots only. Concurrent workflows keep their guards apart, and a caller
outside any job submits and awaits freely (`GuardrailEnforcementTest`).

The job-based kinds are enforced by `GuardrailEnforcer` on the dispatch path, the one
place every route crosses - thinker-driven, doer-direct, fan-out worker, delegation -
so protection is the tool's, never the route's: admission, INPUT content
and auth guards run after dependency resolution and before resources are allocated,
OUTPUT content guards after resources are released and before the result is
delivered. A guard failing with anything but `GuardrailException` fails the gated job
closed with that failure as itself, never laundered into a refusal. Guards run once
per submission, never again on a transparent retry, and the applied counts are written
to `obs.guards_input`, `obs.guards_output` and `obs.guards_validation`, so a zero-guard
execution is an auditable fact. Delegation copies the spawner's tools as well as its
guard: a delegate offers an inherited mutating tool only when no read-only binding
travels with it, and both bindings survive a second hop. Prompt resolution from a job
thread passes the door. At the validation seat of a `SingleObjectiveThinker` a refusal
costs the thinker one iteration and the corrected candidate is delivered; refusals that
exhaust the iteration budget end in a null answer, as when no answer was reached at all.
An `AbstractToollessThinker` allows `MAX_GUARDRAIL_CORRECTIONS` correction turns and then
fails with the refusal.

Guard jobs cross the same scope wall at the internal door, which skips only the
authority and parent walls (a guard is submitted on a gated job's behalf, and the
gated job may be a leaf). The caller guard there is the guard the gated job was
admitted under: an orchestrator's own sealed guard, or a leaf's live parent's - the
parent is the submitter by the door's own rule, and its sealed guard is what the leaf's
claim was judged against. A refusal at the internal door throws to the enforcer rather
than minting a born-failed handle, and the enforcer reports it as a `SystemException`
of the gated job: a guard that never ran rendered no verdict, and a guard whose own
claim disagrees with the flow it guards is a code error, not a policy decision.

## Reference guardrails

One per rung, genuinely usable, doubling as the enforcement-test fixtures
(`GuardrailEnforcementTest`):

| Class | Rung | Demonstrates | Refuses |
|---|---|---|---|
| `PayloadSizeCapGuardrail(parent, maxChars)` | content INPUT | parameterized instance declaration | an input whose serialized JSON is longer than the cap, naming the length, the cap and the gated job's class (`this tool` outside enforcement); a null input passes |
| `PIIDetectionGuardrail` | content OUTPUT | screening a result's serialized form | a result whose serialized JSON matches a Social Security number, a credit card number, an email address or a phone number, naming every kind it found in one refusal and logging that summary at ERROR, never the content; a null result passes |
| `PrincipalAllowListGuardrail(parent, principals)` | auth | judging `snapshot.getUserId()` | a principal outside the set, a missing principal included, naming the principal, the gated job's class and the allowed principals |
| `AgentClassAllowListAdmissionGuardrail(parent, classes)` | admission | the `AdmissionContext` identity payload | a caller class outside the set, naming the tool, the caller (`a direct call` when there is no agent class) and the allowed agents. A missing context is a code error, thrown as `UncorrectableRuntimeLLMException`: the enforcer and ToolHub always deliver one, so a null one means `init` was never called |

Their own logic is pinned in `SampleGuardrailsTest`; both content guards target
`Object`, so they apply to any input or result. The enforcer never hands a guard a null
target: a guard applies only to a target that is non-null and an instance of its
`targetType()`, so the null cases above matter only to a caller running a guard by hand.

The validation rung ships no reference guard: what a valid answer is belongs to the
producer's domain. Its enforcement fixtures live with the test (`RefusingValidationGuard`,
`CrashingValidationGuard`, `TenantClaimingValidationGuard`).
