# Package: ai.redouble.nucleo.tools.guardrails

An agent's model decides which of its tools to call and with what arguments. Those
arguments are text the model wrote, and what a model writes can be steered by anything it
reads: the customer's message, a page a tool fetched, an instruction pasted into a
document. A system prompt that says "only ever look at this customer's orders" is a request
the model usually follows and can be talked out of.

Nucleo keeps the rules that matter out of the conversation's reach. They are Java, the
runtime runs them on every tool call, and nothing the model reads or writes changes them.
This page explains where that line runs and the two things that stand on the application's
side of it: the scope, which fixes what a piece of work is about, and guardrails, the checks
that run before and after every tool call. [Writing guardrails](../../guardrails/PACKAGE.md)
then shows how to write each kind.

## Where the trust boundary runs

In a web application, input from a user is untrusted: you validate form fields, bind SQL
parameters, check permissions before a write. In an agent, the model is that user. It picks
the tool, the entity ids, the amounts, the query. Everything it produces deserves the
treatment a form field from the internet gets: validated, scoped and permission-checked
before it touches your systems.

The boundary runs through the middle of a thinker, the agent whose model decides which tool
to call next ([Tools, thinkers and doers](../PACKAGE.md)). The thinker's Java - its
constructor, what it declares, its loop - is code you wrote and reviewed, and it is trusted.
The model making decisions inside that loop is not. Everything on this page is trusted code
placing a checkpoint in front of that decision-maker, and the model cannot see a
checkpoint, address it or take it away.

Which tools an agent holds is settled by trusted code before any call is made: the tools the
thinker declares in `declareDefaultTools()`, its reconcile hooks, the admission rules of
`ToolHub`, a `SubThinker` copying its parent's list ([Palettes and the tool
registry](../registry/PACKAGE.md)). Even `request_tools`, the one place the model gets a
say in its own tools, goes through admission. What remains to be judged is each call: the
arguments the model wrote, and the person on whose behalf the work runs.

## Scope: the agent is its case

An agent answering one customer's questions should read that customer's orders and nobody
else's. In Nucleo you say so by making the agent be that case. Its scope is a value, such as
`CustomerScope[customerId=C-100]`, that your code sets when it builds the agent. A tool input
that names a customer carries a scope value of its own, the claim, written by the model on
every call. Before the tool runs, Nucleo compares the claim with the agent's scope, and a
call that names any other customer is refused.

[An agent that stays inside its case](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/scope/PACKAGE.md)
builds this in three declarations. The first is the axis: a value that says what the work is
about. A record implementing `Scope` is enough, and two scopes match when they are equal.

<!-- sample: ../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/scope/CustomerScope.java#scope -->
```java
public record CustomerScope(String customerId) implements Scope {
}
```

The second is the marker: an interface extending `Scoped`, with the id getter and a default
`scope()` that turns the id into the value.

<!-- sample: ../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/scope/CustomerScoped.java#marker -->
```java
public interface CustomerScoped extends Scoped {
    String getCustomerId();

    @Override
    default Scope scope() {
        return new CustomerScope(getCustomerId());
    }
}
```

The third puts the marker on both sides of the boundary. On the tool's input it is the
claim, which the model fills in on every call:

<!-- sample: ../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/scope/CustomerOrder.java#claim -->
```java
public class CustomerOrder implements CustomerScoped {
    @LLMRequired
    @LLMDescription("The customer the order belongs to, like C-100")
    private String customerId;
    @LLMRequired
    @LLMDescription("The order number, like A-1042")
    private String orderNumber;
```

On the agent it is the binding, set by code in the constructor:

<!-- sample: ../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/scope/CaseAgent.java#agent -->
```java
public class CaseAgent extends SingleObjectiveThinker<OrderQuestion, OrderAnswer> implements CustomerScoped {
    private final String customerId;

    public CaseAgent(Identifiable parent, String customerId) {
        super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        this.customerId = customerId;
        setAnswerHandler(new PojoResponseHandler<>(OrderAnswer.class));
    }

    @Override
    public String getCustomerId() {
        return customerId;
    }
```

Nothing else is wired: no guardrail class, no registration, no setup per tool. When the
agent is submitted, Nucleo seals its scope onto it, and from then on neither code nor model
output can change it. Every job the agent submits whose input implements the marker is
judged against the sealed scope before the job exists. A call that names C-200 is refused
with a `GuardrailException` whose message is `Scope mismatch: this flow is bound to
CustomerScope[customerId=C-100] but the input names CustomerScope[customerId=C-200]`. The
model receives it as the result of that call, marked as a mistake it can correct, and
answers without C-200's data. Code that submits such a job itself sees the same refusal
through the handle: `get()` throws an `ExecutionException` caused by the
`GuardrailException`.

One rule makes the binding mean something: **the field the scope judges is the field the
tool acts on.** `CustomerOrderTool` looks the order up among the claimed customer's orders
only, so the customer that was checked is the customer whose data is read, and there is no
gap between what was judged and what was executed.

### The scope travels with the work

Work the agent hands on stays inside the case. A sub-agent it delegates to, the workers of a
fan-out, an agent it calls as a tool: each is submitted by the agent, so each inherits the agent's sealed scope, merged with any scope of its own. An inner agent can
add a scope to the ones it inherits and can never shed one.

The read-only binding of a thinker ([Read-only thinkers](../PACKAGE.md#read-only-thinkers))
travels the same way, because it is carried as a scope, `ReadOnlyScope`. A delegate or an
agent-as-tool of a read-only flow is read-only itself, all the way down, and withholds the
mutating tools it inherited. What read-only means is enforced where the tools are offered
(`ReadOnlyPalette`); riding the scope is what makes it travel.

This holds because Nucleo accepts work submitted from inside a job only from an
orchestrator, a thinker or a doer (every orchestrator implements `ScopeAuthority`). A plain
tool, or a thread a tool starts on its own, cannot submit a job, so there is no unguarded
place to submit from.

A piece of work with a different scope is started the only way it can be: trusted code
constructing and submitting a new scoped agent with its own binding. Whether a person may
touch an entity at all is a different question, with the same answer in every flow, and it
belongs to an auth guardrail on the tool (below).

### Scopes that refine one another, and several at once

A scope can refine another: a line item within an invoice. Because this is Java, the
refinement is inheritance. The refining scope is a plain immutable class extending the
parent scope (a record cannot extend a class), and its marker extends the parent marker and
overrides `scope()`. Each level's `matches` compares its own fields on top of
`super.matches`, guarding its cast with `instanceof` so it skips itself for a value that does
not reach its depth. Two related values are then judged on the levels both carry: an
invoice-bound agent pins the invoice of a line-item claim, a line-item-bound agent pins a
plain invoice claim, and two sibling refinements pin the parent they share. The region scope
with its city, district and block refinements in `ScopeValueTest` is the worked example.

An input that claims several unrelated axes at once, such as a project and a user, returns a
`CompositeScope` naming both from `scope()`. A class implementing two markers has to: it
inherits two conflicting default `scope()` methods, and the compiler makes it choose. A
scope whose test is something other than equality, a set or a range, overrides `pass`.

## Guardrails: checks on every call

A scope answers one question: is this call inside what this work is about? Everything else a
call has to satisfy is a guardrail: a check written in Java that Nucleo runs as a job of its
own before or after the tool, whose verdict decides whether the call goes ahead or its
result is delivered.

The question a guardrail helps answer is always the same: may this agent, on behalf of this
person, right now, run this tool with this input? A money-transfer tool shows every part of
it:

- the input - amount, source account, destination account: is the source the principal's,
  is the destination permitted, is the amount within the per-transfer limit;
- the principal - whose authority the agent is spending: the user named when the workflow
  was opened, as `Job.workflow("you", "first-tool")` does in [Your first
  tool](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/tool/PACKAGE.md),
  which every job under it carries;
- right now - today's cumulative transfers against the daily cap, a freeze on the
  destination.

None of these exists before the call and all of them exist at it, which is why a guardrail
runs per call. The input alone never decides: `{amount, A, B}` needs at least one fact that
lives neither in the tool nor in its input, such as who is asking or the running total in a
table. A guardrail receives the input and an immutable snapshot of the call it gates, a
`JobSnapshot` carrying the principal, the tool and where the call sits in the run, and it
looks up anything else by values it finds there. A guardrail is a job, so it can declare the
resources it needs, a database connection for instance, like any tool.

Hard invariants, such as an account that can never be overdrawn, remain the job of the
domain transaction below the agent. The guardrail refuses the attempt before it is made,
with a "no" the model can read and act on.

### Four kinds

Each kind receives one more piece of context than the one before, and the context a check
needs tells you which kind to write. The less a check needs, the closer to the tool it lives
and the more routes it covers.

| Kind | What it receives | The question it answers | Declared on |
|---|---|---|---|
| **Admission** | who is calling, through which agent, to reach which tool - no input yet | may this caller reach this tool at all? | the tool, or an admission rule registered on `ToolHub` |
| **Content** | + the input, or the result | is this data unacceptable in itself? | the tool, facing its input or its result |
| **Auth** | + the snapshot of the call, with its principal | may this person do this to this resource? The answer is the same in every flow; caps and budgets are auth checks whose lookups read a meter | the tool |
| **Validation** | + a thinker still holding its conversation | is this answer right, and if not, what should its author fix? | the thinker, typed to its answer |

Scope sits beside the ladder rather than on it. Its verdict depends on the flow, so the same
call can pass in one flow and fail in another, and the context it needs, the flow's binding,
exists before the call and never changes after it. That makes it a comparison of two values
at submission, with no guardrail job at all.

The ladder is complete: those are all the layers of context a call has, and only a thinker
in the middle of its loop has the last one. A check that seems to need something else is
admission, resource management or domain logic in the wrong place.

### Which way a guardrail faces

A guardrail on the input side protects the tool, and the database, the services and the
world behind it, from the agent: it polices what an untrusted caller is about to make the
tool do. One on the output side faces the other way: it protects the conversation, and
whoever reads it, from what the tool brought back. An output guardrail runs after the tool's
resources are released, which for a transactional tool means after commit, so it controls
where information goes and cannot undo what the tool did.

### Guardrails hold on every route

Nothing in your application or orchestration code runs guardrails. Nucleo runs them where
every job is dispatched, so a tool's guardrails hold however the tool is reached: called by
a thinker's model, submitted directly by a doer, run as a fan-out worker or called by a
delegated sub-agent. Protection belongs to the tool and the flow, whichever
route the call took.

Every refusal is a `GuardrailException`, and it is correctable: the model reads
`Guardrail violation: ` followed by the guardrail's message as the result of its call, and
can change its call or take another way ([When a tool fails](../../harness/errors/EXCEPTIONS.md)).
A guardrail that breaks with any other exception has not refused anything: the call it
gates fails with that exception, and nothing runs unchecked.

## Checks in this package

This package holds four content checks, each ready to declare.

- **`UrlGuardrail`**, for a tool whose input implements `UrlInput`, as `WebFetchInput`, the
  input of `WebFetchTool`, does. It refuses a URL that is blank, is not a valid URI, has no
  host, has a host that does not resolve, or has a host any of whose addresses is a
  loopback, private (RFC 1918) or link-local one, which covers cloud metadata endpoints.
  Each refusal names the
  parameter and the rule it broke and never the value, since the model needs the rule to
  fix the call and the value may be anything it pasted. `WebFetchTool` declares this
  guardrail itself and applies it to every redirect it follows; a
  subclass overriding `isBlocked(String, InetAddress)` sets another policy, such as a domain
  allow-list. The check resolves the host when it runs, so a host that resolves differently
  when the connection is made (DNS rebinding) gets past it; in an untrusted environment,
  isolating the network (a proxy, a DMZ) is the defense.
- **`SizeCapGuardrail`**`(parent, maxChars)` refuses a `Prompt` whose text is longer than
  the cap, and **`ExfiltrationMarkerGuardrail`** refuses a `Prompt` that contains an artifact
  marker of the form `«artifact:...»`. Such markers are how the runtime refers to an
  artifact when it shows a tool's result to a model, and they do not belong in prompt text
  a developer wrote: one found there means content from the artifact registry was smuggled
  into a prompt. Both are
  attached through `Prompts`: `addBaselineGuardrail` for every prompt, `addGuardrail` for one
  key ([Prompts](../../prompt/PACKAGE.md)).
- **`SchemaConformanceGuardrail`** checks a `Skill`: it refuses one with a blank name or no
  body. A suggested tool that `ToolHub` does not know is logged as a warning and not
  refused, because a skill may be published before every tool it suggests is registered.

## How it works inside

Where each check runs in the dispatcher, the rules of the submission door, the evaluation
contract of a guardrail and the type hierarchy are in [Inside the trust
boundary](TOOLS_GUARDRAILS_INTERNALS.md), for those working on the runtime itself.

> **Example:** [An agent that stays inside its case](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/scope/PACKAGE.md) -
> a customer axis in three declarations, and an agent bound to one customer asked about
> another's order. [Overlapping scopes](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/scopes/PACKAGE.md)
> binds one flow to two unrelated axes and narrows one of them partway through.
