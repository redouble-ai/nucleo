# Package: ai.redouble.nucleo.guardrails

[Scope and the trust boundary](../tools/guardrails/PACKAGE.md) introduced guardrails: checks
written in Java that Nucleo runs on every tool call, out of the conversation's reach, under
the identity of the person the work is for. Whatever the model says, the check has already
run. This page writes each of the four kinds and shows the one that ships for each.

## What every guardrail has

A guardrail is a small class extending the base class of its kind:
`AbstractContentGuardrail`, `AbstractAuthGuardrail`, `AbstractAdmissionGuardrail` or
`AbstractValidationGuardrail`. It implements two methods.

- **`targetType()`** names the class of values it judges. Nucleo runs the guardrail only
  for a target that is an instance of it: a guardrail typed to `UrlInput` judges only inputs
  that implement `UrlInput`, one typed to `Object` judges anything.
- **`validate(T target)`** makes the judgment. It returns to let the call through and
  refuses by throwing `GuardrailException` with a message. The model reads that message,
  prefixed with `Guardrail violation: `, as the result of its call, and the refusal is
  correctable: the model can change its call or take another way. So write the message for
  the model: the rule that was broken and what a call that keeps it looks like.

A guardrail is declared as a constructed instance, so its settings are ordinary constructor
arguments. The declare methods run once per call, after the tool's input is set, so a tool
can choose its guardrails case by case with its own logic, and each returns fresh instances:
a guardrail instance judges one call.

Nucleo runs every guardrail as a job of its own and waits for its verdict before the tool
runs, or before the result is delivered. It has a read-only requirement and a ten-second
timeout unless its constructor sets another with `setTimeout`. A guardrail that needs
durable state, rights in a table or a running total, overrides `getRequirements()` to
declare the resource and reaches it through `getResources()` inside `validate`, the way a
tool does ([Your first tool](../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/tool/PACKAGE.md)).

A guardrail that fails with anything other than `GuardrailException` has not refused: the
call it gates fails with that exception as it is, and nothing runs unchecked.

## Content guardrails: the data alone

Reach for a content guardrail when the rule needs nothing but the value itself: an input too
large for the tool, a malformed payload, a URL that points inside your network, a result
carrying personal data or a prohibited link.

A content guardrail faces one way, fixed by its class: `direction()` returns `INPUT` to judge
the tool's input before the tool runs, or `OUTPUT` to judge its result before the result is
delivered. An output guardrail runs after the tool has finished and released its resources,
after commit for a transactional tool, so it decides what leaves the tool and cannot undo
what the tool did. A tool declares its content guardrails of both directions in
`declareContentGuardrails()`:

```java
@Override
public List<ContentGuardrail<?>> declareContentGuardrails() {
    return List.of(new PayloadSizeCapGuardrail(this, 100_000));
}
```

Two ship here, both typed to `Object`, so they apply to any input or result:

- **`PayloadSizeCapGuardrail`**`(parent, maxChars)`, facing the input, refuses an input whose
  serialized JSON is longer than the cap, naming the length, the cap and the tool.
- **`PIIDetectionGuardrail`**`(parent)`, facing the result, refuses a result whose
  serialized JSON contains what looks like a Social Security number, a credit card number,
  an email address or a phone number. One refusal names every kind it found, and that
  summary, never the content, is logged at ERROR.

The checks of `ai.redouble.nucleo.tools.guardrails`, `UrlGuardrail` among them, are content
guardrails too ([Checks in this package](../tools/guardrails/PACKAGE.md#checks-in-this-package)).

## Auth guardrails: the data and who is asking

Reach for an auth guardrail when the rule needs both the value and the person the work runs
for: may this user read this record, move money out of this account, spend more today. Caps
and budgets are auth guardrails whose lookup reads a meter. Its verdict is the same in every
flow, which is why it sits on the tool and holds on every route. It runs on the input side
only, because a refusal has to come before the act.

`getPrincipal()` returns the principal: the user named when the workflow was opened, which
every job under it carries. Everything else the check needs it looks up itself, keyed by
values in the input and the principal. A tool declares its auth guardrails in
`declareAuthGuardrails()`:

```java
@Override
public List<AuthGuardrail<?>> declareAuthGuardrails() {
    return List.of(new PrincipalAllowListGuardrail(this, Set.of("ops-oncall", "release-manager")));
}
```

**`PrincipalAllowListGuardrail`**`(parent, principals)` lets only the named principals invoke
the tool, which is useful for restricting system tools to service accounts. It refuses any
other principal, a missing one included, naming the principal, the tool and the principals
allowed.

## Admission guardrails: who may reach a tool at all

Reach for an admission guardrail when the rule is about who may call what, whatever the
arguments: this tool only through these agents, this capability only for these principals.
A capability that a caller must never have belongs here, and not in a refusal per call.

An admission guardrail has no input to judge: it decides before any call exists. It
implements `checkAdmission()` instead of `validate`, and reads `getAdmissionContext()`, an
`AdmissionContext` carrying the principal, the class of the thinker or doer making the call
(null when the call comes from outside any job, a direct call) and the tool's class. A tool declares its admission guardrails in
`declareAdmissionGuardrails()`, which must not read the input:

```java
@Override
public List<AdmissionGuardrail> declareAdmissionGuardrails() {
    return List.of(new AgentClassAllowListAdmissionGuardrail(this,
            Set.of("ai.redouble.app.rocket.thinkers.PaymentCalculationThinker")));
}
```

A tool's own admission guardrails run in two places. When a thinker asks for the tool
through `request_tools`, `ToolHub` runs them before the tool joins the thinker's palette, so a
refused tool is never offered and the model is told why. And at dispatch they run on every
call, which is the guarantee: the refusal holds for a doer submitting the tool directly and
for every other route. For a tool you do not own, `ToolHub.registerAdmission` attaches an
admission guardrail by thinker class and tool class, by tool name pattern, or by a
predicate over the provider, and `ToolHub` runs it when a thinker asks for the tool through
`request_tools` ([Palettes and the tool registry](../tools/registry/PACKAGE.md)).

**`AgentClassAllowListAdmissionGuardrail`**`(parent, agentClassNames)` pins a sensitive tool
to the agents built around it. It refuses any other caller, a direct call included, naming
the tool, the caller and the agents allowed.

## Validation guardrails: checking an agent's answer

The other kinds guard a tool call. A validation guardrail guards an agent's answer: reach
for it when you have acceptance criteria for what the thinker returns, such as a record
whose every field must agree with what you know.

An output content guardrail on a thinker could only stop its answer on the way out, after
the thinker has finished; its caller would get a failure, and the model that wrote the
answer, the one holding every fact needed to fix it, would never hear about it. A validation
guardrail runs earlier, at the moment a goal-directed thinker has its final answer and still
holds its conversation. A refusal goes back into that conversation as a correction carrying
the guardrail's message, the answer is dropped, and the model answers again with everything
it already knows. The caller never sees a refused answer.

A validation guardrail extends `AbstractValidationGuardrail`, typed to the thinker's answer,
and the thinker declares it by overriding `declareValidationGuardrails()`. The corrections
are bounded. In a `SingleObjectiveThinker` each refusal spends one of the thinker's
iterations, and a thinker that runs out returns a null answer, as when it never reaches
one. An `AbstractToollessThinker` allows two corrections (`MAX_GUARDRAIL_CORRECTIONS`) and
then fails with the refusal.

How the guardrail reaches its verdict is up to you: a field-by-field check in code, a call
to a model under resources the guardrail declares, or a judge thinker it starts. A guardrail
that starts a judge declares `ScopeAuthority`, so the judge is bound to the same scope as the
thinker it judges, and it declares no resources, since it waits on the judge. Its timeout is
the ten seconds of every guardrail unless it sets another.

A thinker can declare both an output content guardrail and validation guardrails. They ask
different questions, "may this leave" and "is this right", and neither runs twice. No
validation guardrail ships: what a valid answer is belongs to the domain of the thinker
that produces it.

## When a guardrail refuses

A model reads the refusal as the result of its call, marked as a mistake it can correct.
Code that submits the tool itself gets it from the handle: `get()` throws an
`ExecutionException` caused by the `GuardrailException`. Every guardrail runs as a job, so
its refusal also reaches the message bus like any failed job, which is how a deployment
sends refusals to an audit table or a security log ([Events and the message
bus](../events/PACKAGE.md)).

## The scope vocabulary

The values the scope is made of live here too: `Scope` and `Scoped` for an axis and its
marker, `CompositeScope` for several unrelated axes at once, `ReadOnlyScope` for the
read-only binding, and `ScopeGuard`, the set of scopes sealed onto an agent. `TenantScope`
and `TenantScoped` are a complete axis in two files, a record and its marker, ready to use
for a tenant id. How to declare and use a scope is in [Scope and the trust
boundary](../tools/guardrails/PACKAGE.md#scope-the-agent-is-its-case).

## How it works inside

The plumbing every guardrail inherits, the declaration interfaces, how each scope value
judges, the rules of the submission door and where the enforcer runs each kind are in
[Inside guardrail enforcement](GUARDRAILS_INTERNALS.md), for those working on the runtime
itself.
