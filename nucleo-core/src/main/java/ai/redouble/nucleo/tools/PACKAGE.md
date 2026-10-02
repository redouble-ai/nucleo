# Package: ai.redouble.nucleo.tools

Your application already has operations it trusts: look up an order, file a claim, send a
notice. A model is good at a different thing: deciding which order a customer means, what to
check next, and when it knows enough to answer. This package is where the two meet. It gives
you three kinds of work to write, and every agent in Nucleo is built from them.

## Tools, thinkers and doers

A **tool** is one of your operations, packaged so that a model can ask for it to be run. It
takes an input bean, returns an output bean, and carries a name and a description the model
reads to decide when to use it. It does the actual work - a query, an API call, a
calculation - and while it runs it holds what the work needs, a database connection or an
HTTP client, and gives it back when it ends. [Your first
tool](../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/tool/PACKAGE.md)
writes `OrderStatusTool`, which finds an order by its number.

A **thinker** is an agent. It gives a model an objective - its instructions and the input it
was given - and a set of tools, its palette, and runs a loop: the model reads the situation
and either asks for tools to be called or answers. Nucleo runs the calls, shows the model what
came back, and asks again, until the model answers in the class the thinker declared. [Your
first agent](../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/agent/PACKAGE.md)
writes `OrderAgent`, whose palette is `OrderStatusTool`.

A **doer** does the same job as a thinker - it calls tools and agents and returns a typed
answer - with the order of the work written in Java instead of chosen by a model. [Your first
doer](../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/doer/PACKAGE.md)
writes `CustomerReportDoer`, which looks up every order of a customer at once and then asks a
model one question over the results.

Thinkers and doers are tools themselves: `Thinker` and `Doer` both extend `Tool`. Anything
that can call a tool can call an agent or a doer. Your code runs all three the same way - set
the input, submit it to the `JobDispatcher`, wait for the result - and an agent's palette can
hold another agent or a doer beside plain tools.

### Choosing between a thinker and a doer

The question is who decides what happens next: your code or the model.

| The work | Write |
|----------|-------|
| Known steps over data that varies | a doer (`AbstractDoer`) |
| Steps the model should choose from what it reads | a thinker (`AbstractThinker`) |
| Branching you can write as if/else | a doer |
| Behavior that adapts to what the tools return | a thinker |
| Retrying with backoff | a doer |
| Correcting itself when a tool refuses its input | a thinker |

Both hold no resources, both can run for hours, and both can be called as tools.

## Why thinkers and doers hold nothing

An agent runs for minutes, waiting on a model most of that time. A database connection
should be held for milliseconds. If an agent held a connection while it waited, a hundred
agents running at once would exhaust any connection pool.

So Nucleo splits the work in two. Tools do the work and hold resources while they do it.
Thinkers and doers - orchestrators, together - coordinate and hold nothing: they submit jobs,
wait on the handles, and each job gets its own resources, runs, and gives them back.

| | **Tools** | **Thinkers and doers** |
|---|-----------|-------------------|
| **Purpose** | Do a single thing | Coordinate many things |
| **Resources** | Held while the tool runs | None |
| **Duration** | Milliseconds to seconds | Minutes to days |
| **Timeout** | Always one | None: they only wait on tools, and every tool has its own |
| **Examples** | Search a database, call an API, extract text | Run the tool loop, fan out and in, pipelines |

The result is that you write plain, single-threaded Java. You write a tool that queries the
database and a thinker that calls tools, and you never manage connection pools, threads,
timeouts or cleanup. Nucleo:

1. **provides resources** when your code runs, and not before,
2. **releases them** when your code finishes, and not after,
3. **enforces a timeout** if your code takes too long,
4. **isolates failures**, so one tool cannot break another.

A tool's timeout is 30 minutes unless its constructor sets another with `setTimeout`; the
built-in tools set their own, from one second for the clock to six minutes for a model call.
An orchestrator refuses a timeout: `setTimeout` on a thinker or doer throws. While an
orchestrator waits on a handle it blocks a virtual thread, which costs next to nothing.

## Writing a tool

[Your first tool](../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/tool/PACKAGE.md)
shows the whole shape: extend `AbstractTool<I, O>`, name and describe the class with
`@ToolName` and `@ToolDescription`, ask for resources in `getRequirements()`, and do the work
in `execute`. Every tool needs a public constructor taking the parent,
`public MyTool(Identifiable parent)`: when a model calls the tool, Nucleo builds a fresh
instance through it, with the calling agent as the parent, so each call has an instance of
its own.

### Saying that a tool changes nothing

`@ToolDescription` has a `readOnly` flag, `false` unless you set it. `OrderStatusTool`
sets it:

```java
@ToolName("order_status")
@ToolDescription(value = "Look up one order by its number: its status, its customer, the date it was promised for", readOnly = true)
```

`readOnly = true` is a promise that the tool observes the world without changing it, and it
decides where the tool may run: a thinker bound to look without touching (below) is offered
read-only tools and nothing else. Setting the flag forces the named form `value = "..."`,
because Java allows the shorthand only while `value` is the one element present.

A tool is read-only when nothing anyone observes afterwards has changed. Reads of every kind
qualify: database SELECTs, HTTP GETs and search endpoints, pure computation, model calls,
reading files, reading the artifact registry. A model call qualifies because the model
returns text and changes nothing the next caller can see. A tool that writes or deletes
database rows or files, sends mail or messages, or otherwise leaves the environment
different from how it found it is not read-only.

What counts is the observable result, whatever bytes were written along the way. Bookkeeping
a service creates in order to answer a read does not count: a search engine caching the page
it fetched, or a document-parsing service holding an uploaded copy and a job record so it
can return the text, are how the read works, and nothing downstream differs because they
happened. That is why a document-parsing tool is read-only although it uploads bytes and gets
job ids back: it is how an agent reads a PDF. Producing a new document from an input is a
read as well, since the input and everything else stay untouched.

An unmarked tool is treated as one that changes things. Marking it is an assertion the
author makes and a reviewer can check, so an unmarked tool never has to be told apart from an
unreviewed one.

A doer or a thinker may be marked read-only only when everything it can reach is read-only
too. For a doer that means every job it submits. For a thinker it means its whole palette,
through any agents in it, and a palette that cannot grow: a thinker that can widen its own
palette while it runs, through `request_tools` or through a `sub_thinker` inheriting a tool
that changes things, cannot be marked. Orchestrators whose effect is whatever the caller
names - `request_tools`, `sub_thinker`, and any fan-out doer running a worker the caller
chose - can never be marked, so they keep the default.

### Telling the model how heavy a tool is

The model sees each tool's description with a short tag after it, such as
`[api call, weight: 10, read-only]`. `@ToolWeight` sets it: the kind of work
(`ToolType.IN_MEMORY`, `DATABASE`, `API_CALL` or `THINKER`), a weight from 0 to 100 as `min`
and `max`, and for an agent the `level`, how many layers of agents sit beneath it. The model
uses it to spend effort in proportion, for example to skip a heavy agent for a trivial
question. A tool without the annotation is tagged as an API call of weight 10, and an agent
as a thinker of weight 20 to 80. `@DisplayName` gives the tool the name that progress
notifications show a person.

### Values known only at runtime

A tool's input schema is generated from its input class. The class can state a parameter's
type and meaning, but not a set of values that exists only once the application is running:
the columns of a table, the names in a registry. A model told "a column name" guesses one, is
refused and calls again; a model told the names never guesses. For such a parameter, declare
`@SchemaRefinedBy(SomeRefiner.class)` on the tool. The `SchemaRefiner` (with a public
no-argument constructor) is given the input class and the generated schema, and writes the
names in as an `enum`.

The refiner runs once per tool provider, and the one refined schema then reaches everyone who
reads it: the tool definition the model sees, the MCP publication in every dialect, and the
check that judges a call, which refuses a wrong name with the accepted names listed and the
guess left out. A refiner that fails is a defect of the tool and throws, so a tool never
publishes the schema that invites the guessing the refiner exists to end. A typical use is a
refiner per table: the sortable columns written in under the sort parameter, the grouping
columns under the grouping, every visible column under the filter.

### When a tool fails

A tool reports failure by throwing a typed exception that says whose problem it is, and the
thinker passes its explanation to the model. `InvalidInputException` means the caller asked
the wrong way, and its message says how to ask right, so the model can correct the call and
try again. `ResourceNotFoundException` means the thing asked for by its id does not exist.
`ExternalServiceException` means a service the tool depends on failed, and trying the same
call again will not help. The whole family is in [Failures that
behave](../harness/errors/EXCEPTIONS.md).

`execute` declares `throws LLMReadableCheckedException`, so the body ends with one catch for
everything else it can throw:

```java
catch (Exception e) {
    throw LLMReadableCheckedException.unwrap(e);
}
```

`unwrap` passes typed failures through unchanged, reports anything else as a failure the
caller can read, and lets the runtime's own retry signals through - upstream throttling, a
correction of the model's answer, an escalation after a truncated answer - so the broad catch
never turns something the dispatcher knows how to retry into a failure.

A tool that calls an external service with a value the model supplied wraps that one call
with the parameter it was made for:

```java
String xml;
try {
    xml = client.getAsXml(path);
}
catch (Exception e) {
    throw LLMReadableCheckedException.wrapWithContext(e, "EPO OPS", "patentNumber", input.getPatentNumber(), "fetching the claims");
}
```

`wrapWithContext` keeps the distinction between what the model can fix and what it cannot. A
failure the model can fix (a 400, a 404 on a fetch) comes back as an `InvalidInputException`
naming the parameter as the model knows it, so the model knows which of its inputs to change.
Any other failure, or a raw `IOException`, comes back as an `ExternalServiceException` naming
the service and the step. Retry signals pass through it as through `unwrap`. The tool's own
checks (a missing parameter, a status it classifies itself) stay outside the wrap, and the
catch with `unwrap` stays for the rest of the body. `EPOClaimsTool`, `PubMedFetchTool` and
`WebFetchTool` are written this way.

### A tool that asks a model one question

Some tools are one exchange with a model: summarize this text, classify this message. Such a
tool extends `AbstractModelDependentTool<I, O>`, passes the grade of model it needs to the
constructor, and lets Nucleo hold the model for it. `AnalyzeViolationTool`, which judges
whether a guardrail refusal was the agent's mistake or an attempt to steer it, is the whole
pattern:

```java
public AnalyzeViolationTool(Identifiable parent) {
    // A small, fast grade suffices for security analysis
    super(parent, "ANALYZE-VIOLATION", Grade.SMALL);
    // 6 min: hung-call guard for one LLM call - cold start, long reasoning, and in-call retry
    // can run far past the typical few seconds. A ceiling, not expected latency (matches LLMCall).
    setTimeout(Duration.ofMinutes(6));
}

@Override
public JobRequirements getRequirements() {
    JobRequirements req = new JobRequirements();
    // ViolationAnalysisOutput is a verdict with a short explanation: a handful of fields
    wireConversation(req, Depth.STANDARD, OutputDeclaration.of(OutputSize.COMPACT), ViolationAnalysisOutput.class, INSTRUCTIONS);
    req.setRequiresTransaction(false);
    return req;
}

@Override
public ViolationAnalysisOutput execute(JobResources resources, JobContext<ViolationAnalysisOutput> context) throws LLMReadableCheckedException {
    try {
        context.publish("Calling security analysis model", 30);
        ViolationAnalysisOutput output = converse(resources);
        context.publish("Analysis complete", 100);
        return output;
    }
    catch (Exception e) {
        throw LLMReadableCheckedException.unwrap(e);
    }
}
```

`wireConversation` builds the request once: the tool's input written out as the prompt,
followed by the instructions, with the output class as the answer the model must give.
`converse` sends it and returns the answer as that class. An answer that does not fit goes
back to the model as a correction, up to twice, exactly as an agent's answer does ([Answers as
Java objects](../harness/schema/PACKAGE.md)). A tool whose request the prompt cannot express,
one carrying images or files, builds the conversation itself and passes it to the other
`wireConversation`, and is corrected the same way. `QuickLLMQuestionTool` is the reference
implementation. A tool that calls no model at all stays on `AbstractTool`.

### Running on one exact model

Everything that holds a model seat - a thinker, a one-call tool, the call a thinker makes each
turn - implements `ModelDependent`. It declares a grade, and `pinModel` can pin it to one
exact catalog entry for a run instead. Pinning a thinker pins every call it makes, so its
whole conversation runs on that entry. A pin skips the picker and nothing else: the checks on
what may serve the work still apply. [Measuring the grade a job
needs](benchmark/PACKAGE.md) uses this to race one job on every model of its grade, and [The
catalog, grades and the picker](../harness/models/PACKAGE.md) explains grades.

## Writing a thinker

[Your first agent](../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/agent/PACKAGE.md)
shows the shape of a thinker working toward one answer: extend
`SingleObjectiveThinker<I, O>`, whose input extends `ThinkerInput` and whose answer extends
`ThinkerOutput`, and then

- pass a `ThinkerDeclaration` to the constructor (below),
- set the answer handler in the constructor, `setAnswerHandler(new PojoResponseHandler<>(OrderAnswer.class))`;
  without one the first turn fails, since the thinker cannot read an answer whose type it
  does not know,
- return the instructions from `getSystemPromptText()`, annotated `@StaticPrompt` so a
  deployment can substitute them ([Prompts](../prompt/PACKAGE.md)),
- return the palette from `declareDefaultTools()`.

`declareDefaultTools()` runs inside the base class's constructor, before your constructor's
body, so it cannot read fields your constructor sets. A palette that depends on the input
belongs in `reconcileToolRegistry()` (below).

Beside the tools you declare, every thinker is given two tools for reading its artifacts in
full, `get_artifact_field` and `search_artifact_content` ([Artifact
tools](../harness/artifacts/tools/PACKAGE.md)), `sub_thinker` for delegating part of the work
(below), and any tool registered for all thinkers with
`ToolHub.getInstance().registerSystemWideTool`. When it declares tools or skills the model
may ask for, it is also given `request_tools` and `request_skill` ([Palettes and the tool
registry](registry/PACKAGE.md)).

### What a thinker declares

Every thinker states three things about the model it needs, and none has a default: a
thinker without its `ThinkerDeclaration` does not compile.

- **Grade**: the lowest rung of model that can do the work, from `Grade.MICRO` to
  `Grade.MEGA`, or `Grade.CEILING` for the strongest model the deployment serves. The
  deployment's picker turns the grade into a model on every call. A caller may raise the
  grade for a particular run with `setGrade`. `QuickLLMQuestionTool` is the one tool whose
  grade comes with its input: it is how code or a model asks one question without writing a
  thinker, so the asker names the rung in `QuickLLMQuestionInput.grade`, which is required.
- **Output size**: how much answer each turn may produce. It becomes the turn's `max_tokens`
  and the amount reserved against the provider's rate limit. Choose it by the shape of your
  answer class, sized for the largest turn the thinker produces, since a turn may be a tool
  call or the final answer: `OutputSize.VERDICT` for a label, a boolean or a one-field class;
  `COMPACT` for a class with a handful of fields, a short list, a tool call with its
  arguments; `STANDARD` for a rich class, a list of records or a table, the typical final
  answer; `EXTENDED` for a written section, a report or a long list of extractions; `MAX` for
  as much as the model can produce. Unless the catalog entry declares its own budgets, the
  rungs are 1,024, 4,096, 16,384 and 32,768 tokens, each capped at the model's own limit. A
  thinker whose answer fits no rung declares a count instead: `new ThinkerDeclaration(grade,
  tokens)`, or `setOutputBudget(int)` later.
- **Depth**: how hard to work, from `Depth.IMMEDIATE` to `Depth.ULTRA_THOROUGH`. It comes with
  the input, `ThinkerInput.depth`, `STANDARD` unless the caller sets another, and a thinker
  called by another thinker never works deeper than its caller. The model reads the depth as
  an instruction, and the catalog entry turns it into a budget of reasoning tokens.

`OutputSize.STANDARD` and `Depth.STANDARD` share a name on purpose. Import neither enum
statically, so every line says which STANDARD it means.

A turn that runs out of room is re-run once with the model's full output allowance. That is a
safety net for a thinker one rung too low. When a deployment records its model calls, the
output tokens of a thinker's calls show its real size, and the smallest rung above the
largest is the declaration; a thinker whose calls stop on `MAX_TOKENS` needs the next rung.
The declaration can also carry a comfort window and a compaction trigger for long
conversations ([Conversations](../harness/conversation/PACKAGE.md)).

### What happens on each turn

The model is sent the objective, the conversation so far and the definitions of the tools in
the palette. It replies with tool calls or a final answer.

Tool calls of one turn run at the same time: every call is submitted before any is awaited,
so a turn that asks for three lookups waits for the slowest one. A tool that changes the
turn's own palette or conversation, such as `request_tools`, finishes before the next call
is submitted. Each result goes back to the model, and so does each failure, as the
explanation of the exception the tool threw: the model sees why a call failed and whether
trying again can help, and nothing in your thinker has to handle it.

A final answer is read into your answer class. An answer that does not fit goes back to the
model to be corrected ([Answers as Java objects](../harness/schema/PACKAGE.md)); the artifacts
it names are looked up ([Artifacts](../harness/artifacts/PACKAGE.md)); and the checks the
thinker declares in `declareValidationGuardrails()` run against it while the conversation is
still open. A check that refuses the answer sends the reason to the model as a correction,
and the model answers again with everything it knows ([Scope and the trust
boundary](guardrails/PACKAGE.md)).

A thinker stops after `SingleObjectiveThinker.DEFAULT_MAX_ITERATIONS` turns, 50, unless
`setMaxIterations` sets another limit. A run that reaches the limit ends without an answer,
and the thinker's result is null. A conversation that outgrows the model's window is compacted
first ([Conversations](../harness/conversation/PACKAGE.md)).

The final answer of a thinker your code submitted is also published as a `ContentStreamEvent`,
for a frontend watching the run. A thinker called as a tool returns its answer to the thinker
that called it instead.

### Changing the palette while the agent runs

`addTool` and `removeTool` change a thinker's palette from outside. For a palette that follows
the thinker's own state, override `reconcileToolRegistry()`: it runs before every turn, and
what it leaves registered is exactly what the model sees and may call. Keep it idempotent -
the same state produces the same registry however often it runs - which is easy because
`ToolRegistry.register` and `unregister` do nothing when the registry is already as asked.
Call `super.reconcileToolRegistry()`, which offers `sub_thinker`, `request_tools` and
`request_skill`. [Palettes and the tool registry](registry/PACKAGE.md) covers the catalog a
model may request tools from and the rules that admit them.

### A thinker that can only look

A supervisor, an auditor or a reviewer should observe without acting. Any thinker can be bound
to tools that change nothing by passing `true` as the third argument of its constructor:

```java
public class MySkeptic extends SingleObjectiveThinker<SkepticInput, SkepticOutput> {
    public MySkeptic(Identifiable parent) {
        super(parent, new ThinkerDeclaration(Grade.MEDIUM, OutputSize.STANDARD), true);   // read-only for its whole life
        setAnswerHandler(new PojoResponseHandler<>(SkepticOutput.class));
    }

    @StaticPrompt
    @Override
    protected String getSystemPromptText() {
        return """
            Check every order status the reply states against the order itself, and say
            which statements the lookup confirms and which it does not.
            """;
    }

    @Override
    protected List<Class<? extends Tool>> declareDefaultTools() {
        return List.of(OrderStatusTool.class);
    }
}
```

`SkepticInput` and `SkepticOutput` are the author's own `ThinkerInput` and `ThinkerOutput`.

A bound thinker is harmless by construction instead of by the care of whoever chose its
tools. The model is never shown a tool that changes things, and a call to one is refused as a
mistake it can correct. That lets a caller hand over a superset: the tools of every thinker a
doer supervises can be passed in wholesale, and what survives is exactly their observational
part. The caller does not curate, and so cannot curate wrongly. Each tool withheld is logged,
so a palette that shrank can be told apart from one that was never filled - which matters
when a skeptic reports it could not verify something.

The binding is a constructor argument held in a `final` field, because what makes a
supervisor trustworthy is that it never ran anything that changed something; a switch could
only promise "read-only from now on". It is available on `AbstractThinker`,
`SingleObjectiveThinker` and `ReactiveThinker`, so it combines with any shape of thinker. It
also travels: every sub-agent of a bound thinker, every agent it calls as a tool, and their
own descendants are bound too, and a flow bound to one case keeps both its case and its
binding through every delegation ([Scope and the trust boundary](guardrails/PACKAGE.md)).

### Other shapes of thinker

`SingleObjectiveThinker` works toward one answer. A chat turn over a conversation that lasts
is a `ReactiveThinker` or a `ChatThinker`, and a single model call with no tools that still
wants a thinker's declaration and checks is an `AbstractToollessThinker`. [The thinker
families](thinking/PACKAGE.md) compares them.

A decision model writes no text: it reads a state and typed questions and answers each with a
probability over options the caller declared. An agent on one is `DecisionThinker`, an
orchestrator over a palette of tools that turn artifacts into artifacts: code computes the
legal moves from the types, the model ranks them, and the answer is the artifacts it selects.
[What a decision model is](deciding/PACKAGE.md) says what a decision model is good and bad at,
and when to use it instead of the thinkers above.

## Agents calling agents

Because a thinker is a tool, it can sit in another thinker's palette. The coordinator's model
sees every entry as a tool it may call, and Nucleo handles the difference between a tool that
answers in a second and an agent that works for minutes:

```java
public class ResearchCoordinator extends SingleObjectiveThinker<ResearchQuery, Report> {
    public ResearchCoordinator(Identifiable parent) {
        super(parent, new ThinkerDeclaration(Grade.LARGE, OutputSize.EXTENDED));
        setAnswerHandler(new PojoResponseHandler<>(Report.class));
    }

    @StaticPrompt
    @Override
    protected String getSystemPromptText() {
        return """
            Answer the research question from the literature, the web and the data, using
            the agents and the tool you are given.
            """;
    }

    @Override
    protected List<Class<? extends Tool>> declareDefaultTools() {
        return List.of(
            LiteratureAggregator.class,    // Thinker - runs for 5 minutes
            WebAggregator.class,           // Thinker - runs for 3 minutes
            DataAnalysisTool.class         // Tool - runs for seconds
        );
    }
}
```

`LiteratureAggregator` is the literature agent of `nucleo-ext-lit` ([Literature
search](../../../../../../../../nucleo-ext-lit/src/main/java/ai/redouble/nucleo/ext/lit/PACKAGE.md)).
`ResearchQuery`, `Report`, `WebAggregator` and `DataAnalysisTool` stand for the author's own
input, answer, agent and tool.

Three things change when a thinker is called as a tool:

- **It works no deeper than its caller.** The depth the caller's model gives it is lowered to
  the caller's own when it asks for more.
- **It sees only the artifacts it is handed.** The data its caller holds does not flow in on
  its own: the caller's model lists the references of the artifacts it passes in the input's
  `artifactRefs`, and the called agent can read those and nothing else. The artifacts it
  selects for its own answer come back to the caller exactly as its tools produced them
  ([Artifacts](../harness/artifacts/PACKAGE.md)).
- **Its failure is its caller's information.** When it fails, the caller's model reads the
  failure as the result of that call and can try another way.

### Letting the model delegate: `sub_thinker`

Every thinker working at `Depth.STANDARD` or deeper is offered `sub_thinker`, which starts a
sub-agent on a part of the problem the model chooses. The model writes the sub-agent's
objective, the depth it should work at, the references of the artifacts it hands over, and a
list of exclusions. As with any agent called as a tool, the sub-agent can read only the
artifacts listed in `artifact_refs`; a reference that appears in the objective alone is not
handed over. The sub-agent has every tool of its parent, works at its parent's grade, and
answers with a `SubThinkerOutput`: a summary of its findings, its reasoning, and the artifacts
it found, which reach the parent intact. A sub-agent may itself delegate only when it was
started at `Depth.THOROUGH` or deeper, so one level of delegation is the rule. It carries its
parent's scope and read-only binding.

```json
{
  "tool": "sub_thinker",
  "input": {
    "query": "Investigate hepatotoxicity signals for «artifact:link:patent~a7f3b2»",
    "depth": "STANDARD",
    "artifact_refs": ["«artifact:link:patent~a7f3b2»"],
    "exclusions": ["mechanism of action", "pharmacokinetics"]
  }
}
```

The exclusions keep parallel sub-agents apart. When a model starts several over the same
material, it lists in each the topics the others are covering, and the sub-agent's
instructions tell it to leave those alone and pick searches that steer away from them. Without
that, sub-agents over one corpus tend to find the same top results and reach the same
conclusions.

A thinker changes when it offers `sub_thinker` by overriding `delegationThreshold()`.

## Writing a doer

[Your first doer](../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/doer/PACKAGE.md)
shows the shape: extend `AbstractDoer<I, O>` and write `execute(JobContext<O> context)`,
which declares `throws LLMReadableCheckedException`. Waiting on a handle throws checked
exceptions of its own, so the body sits in a `try` whose catch is
`throw LLMReadableCheckedException.unwrap(e)`. The code reads as sequential Java, and every
job the doer submits gets its own resources for as long as it runs; a doer that takes two
hours wastes nothing.

A doer submits its jobs through three helpers, which record the order the work ran in:

- **`submitInStep(job)`** for work done one after another. Each call is the next step, so a
  doer that calls A, then B, then C records them as steps 1, 2 and 3.
- **`nextStep()`, then `submitInCurrentStep(job)`** for work done at the same time. Claim a
  step once with `nextStep()` and submit every parallel job with `submitInCurrentStep`: jobs
  that share a step are recorded as having run in parallel.
- **`submitInCurrentStep(job, dependencies)`** for work that must wait for other jobs: the job
  is held until every job in `dependencies` has finished, and reads their results through
  `JobContext.getDependencyResults()`.

Submit every child through these helpers. A job submitted straight to the dispatcher carries
no step, and the record of the run has no place for it. A thinker's turns are numbered the
same way without your help: each turn's model call and the tools it asked for share the
turn's number.

## Putting agents together

Nucleo imposes no topology. The patterns are built from the pieces above:

| Pattern | Built as |
|---------|----------------|
| **Pipeline** (A → B → C) | A doer waiting on each job before submitting the next |
| **Fan-out / fan-in** | A doer submitting N jobs in one step and collecting their results |
| **Hierarchy** | A thinker with other thinkers in its palette |
| **Router** | A doer or thinker choosing one specialist for the input |
| **Debate** | A doer running a proposer and a critic, then a judge over both answers |
| **Voting** | A doer running N solvers and an aggregator that depends on all of them |
| **Escalation** | A doer running a job at a small grade, and again at a larger one (`setGrade`) when the answer falls short |

### Pipeline

```
Input → [Job A] → [Job B] → [Job C] → Output
           │          │          │
           └── get ───┴── get ───┘
```

Each job waits for the one before it: the doer submits A, waits, submits B, waits, submits C.

### Fan-out / fan-in

```
                 ┌─► [Job 1] ─┐
                 │            │
Input → [Doer] ──┼─► [Job 2] ─┼──► Aggregate → Output
                 │            │
                 └─► [Job N] ─┘
                   (parallel)
```

The doer submits one job per item, collects every result and combines them.

A fan-out is general enough to write once, as a doer of your own that serves every list: the
calling thinker passes the reference of a list artifact, the name of a worker tool and an
instruction; the doer runs a fresh worker per item (a tool or a thinker alike), checks every
typed result, and returns a new `ListArtifact` in the order of the items. `nextStep` and
`submitInCurrentStep` are all it needs from Nucleo.

### Hierarchy (agents as tools)

```
                    [Coordinator Thinker]
                            │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
        [Sub-Thinker]   [Tool]      [Sub-Thinker]
              │                           │
        ┌─────┴─────┐               ┌─────┴─────┐
        ▼           ▼               ▼           ▼
     [Tool]      [Tool]          [Tool]      [Tool]
```

The coordinator's model decides which agents to call, and each runs its own tool loop.

### Router

```
                 ┌─► [Legal Specialist] ────┐
                 │                          │
Input → [Router]─┼─► [Medical Specialist] ──┼─► Output
                 │                          │
                 └─► [Financial Specialist]─┘
                   (one path per request)
```

The router classifies the input and hands it to one specialist; only that path runs.

### Voting

```
        ┌─► [Solver A] ─► answer₁ ─┐
        │                          │
Input ──┼─► [Solver B] ─► answer₂ ─┼─► [Vote] → Output
        │                          │
        └─► [Solver C] ─► answer₃ ─┘
              (parallel)
```

The same input goes to several solvers (different models or instructions), and the answers are
combined by majority, by weighted vote or by a judging model.

### Escalation

```
Input → [Cheap/Fast] ──► confident? ──yes──► Output
                              │
                              no
                              │
                              ▼
                       [Expensive/Capable] ──► Output
```

A small, fast model answers first; when its confidence falls below a threshold, a larger model
answers.

### Debate

```
        ┌─► [Proposer] ─► position ─┐
        │                           │
Input ──┤                           ├─► [Judge] → Output
        │                           │
        └─► [Critic] ───► critique ─┘
```

Two agents argue positions, and a judge picks one or combines them. The exchange can repeat.

## When things fail

Tools throw `LLMReadableCheckedException` subclasses. Code where a checked exception cannot
propagate - a stream, a callback, reading an enum - throws `CorrectableRuntimeLLMException` or
`UncorrectableRuntimeLLMException`. The thinker hands either kind to the model: a correctable
failure lets it fix its parameters and try again, an uncorrectable one tells it to take
another approach.

The loop itself fails in a few ways:

- **The model returns no response.** The thinker fails with
  `ExternalServiceException("LLM", ...)`.
- **The model answers with neither tool calls nor a final answer.** A `SingleObjectiveThinker`
  tells it so and asks again. A `ReactiveThinker` asks once for the answer, and fails with
  `SystemException("AgentLoop", ...)` when the second reply is empty too.
- **The conversation no longer fits the model.** It is compacted, tool results first, and the
  call is retried; when it still does not fit, the thinker fails with
  `ContextOverflowException`. In a chat, the user's messages and the final answers are kept
  word for word.

Where a failure ends up depends on how the thinker was called:

| Called as | What happens |
|-----------|--------------|
| A chat turn (`ReactiveThinker`) | The turn fails with the cause; the conversation is rolled back to the last user message and keeps a marker saying why, and the next message resumes it |
| A tool of another thinker | The caller's model reads the failure as the result of that call |

## Threads

| Part | How it is used |
|------|----------------|
| `ToolRegistry` | Safe to share between threads |
| A thinker | One thread, one conversation per instance |
| A tool | One instance per call |

## Tools that come with Nucleo

[Built-in tools](builtin/PACKAGE.md) lists the tools any thinker can declare: exact date and
rate calculators, the clock, one question to a model, summarizing text of any length, and
fetching a web page. Tools on an MCP server join a palette the same way as a class
([Calling MCP servers](../mcp/PACKAGE.md)). A question for a decision model - which team, is this
urgent, how severe, are these two the same - is one job, `DecisionCall`: a `DecisionRequest`
in, the answers with their probabilities out, on the deployment's decision model. It is never
in a palette ([What a decision model is](deciding/PACKAGE.md)).

## How it works inside

The class hierarchy, the model-seat and one-call tool mechanics, the benchmark's internals,
how the read-only binding is enforced, the loop turn by turn, the answer-shape boundary, the
reconcile hooks and the meta-tool providers are in [Inside tools, thinkers and
doers](TOOLS_INTERNALS.md), for those working on the runtime itself.

> **Example:** [Your first tool](../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/tool/PACKAGE.md),
> [Your first agent](../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/agent/PACKAGE.md) and
> [Your first doer](../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/doer/PACKAGE.md) - one
> order lookup as a tool, handed to a model as an agent's palette, and fanned out by a doer.
