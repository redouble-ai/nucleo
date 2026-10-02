# Inside decision calls and the decision thinker

This page is for people working on the runtime itself. What a decision model is, when to
use one, and how to write a decision call or a decision thinker are in
[the guide](PACKAGE.md); this page holds the classes, the exact shape of a thinker's turn,
and the contract the tests pin. The vocabulary of a decision (the questions and the
answers) is `harness.decision`, the client family is `harness.llm.DecisionClient`, and the
catalog and admission side is `harness.models`; this package holds the jobs that put the
model to work.

## What is here

| Class | Role |
|---|---|
| `DecisionCall` | One decision as a job: a `DecisionRequest` in, a `DecisionResponse` out. Declares the decision seat, so it goes through the whole door: resolution to the deployment's decision model (`ModelPicker.decisionSpec`, the `decision` pin), pricing of the request text against the entry's context ceiling, admission on the entry's account, the spend gates. Its requirements are read-only, since a decision reads and writes nothing of the deployment's. Holds the decision client for one round trip. Not a palette tool: it carries no tool name, so no thinker's model can call it; a chat model asking a decision model is a design a caller makes deliberately, by wrapping this in a named tool with a schema it authored. `pinModel(spec)` names the exact entry; the timeout is `DEFAULT_TIMEOUT`, one minute, a hung endpoint's worth. |
| `DecisionThinker<I, O>` | An agent whose model is a decision model: an orchestrator over a palette of `DecisionTool`s, with an objective in words. Every turn the model ranks the legal moves; code runs the winner; the answer is a `ListArtifact<O>` of the artifacts the model selects when it finishes. Abstract: a subclass names the output type, the key tool, the rest of the palette and the objective in its constructor. `paletteNames()` is the palette by tool name, in the order the model is offered it. |
| `DecisionTool<I, O>` | The type of every tool a decision thinker may hold: an `AbstractTool` whose input and output are both artifacts. Everything around a decision model is an artifact, and this type is where that rule lives. |
| `DecisionTurn` | The record of one turn: the distribution over the moves offered, the tool chosen, the distribution over its candidate artifacts, the artifact it ran on, what it produced or how it failed, and the selection probability of every artifact of the output type (an unmodifiable map in the order asked, empty and never null on a turn that put nothing to the model). `FINISH` is the name of the move that ends a run; `finished()` says whether the turn did. |

## One turn of the thinker

The registry is the world (`harness.artifacts.ArtifactRegistry`, the input artifact its
first entry), and a move is a tool applied to an artifact in it whose type the tool takes,
or `finish`. Code lists the legal moves from the types, every turn:

- a tool is offered when some artifact of its input type is in the run's world, which is
  every artifact the run adopted (the input, every tool's product) and the iterands of every
  adopted `ListArtifact`, recursively, each counting by its own type (the list itself is a
  candidate only for a tool that takes a list), and the pair (tool, artifact) has not run.
  The world keeps the order things came to be, so the lines the model reads and the options
  it chooses among are in one order every run, never a hash order: a decision model is
  sensitive to option order, and a run's record must repeat;
- `finish` is offered once a tool has produced an artifact of the output type; the input is
  never an answer, whatever its type.

One round trip per turn carries every question at once, independent of one another, since
the model's output is free and the state is paid once: a `Choice` over the offered tools plus
`finish` (question id `next`), a `Choice` per offered tool over its candidate artifacts (id
`<tool>_input`, read only for the winner), and a `Noul` per artifact of the output type,
"does this belong in the answer" (id `select_<ref key>`, read only on a finish). The state the
model reads is the objective, one digest line per artifact (`ref (alias): words`, the words
cut at `DIGEST_CHARS`, 160 characters, with an ellipsis), and the moves made so far with
their outcomes (`nothing yet` before the first). The words are a
list's count and iterand type, else what the type's formatter in `TextFormatterRegistry`
says, on one line (an application registers one per artifact type it puts before a decision
model: the digest is all the model sees of an artifact, so the formatter is where the
application decides what a file, a document or a statement reads as), else a link's title,
else the artifact's summarized JSON.

- Termination is by construction: a pair runs at most once, so the move set shrinks every
  turn. `setMaxTurns` (default `DEFAULT_MAX_TURNS`, 32) is the belt: when the budget is spent
  the run asks the selection alone and finishes with what the model selects then.
- The answer is the artifacts of the output type whose selection probability reaches
  `setSelectionThreshold` (default `DEFAULT_SELECTION_THRESHOLD`, 0.5), as a `ListArtifact<O>`
  whose instruction is the objective, registered in the run's registry. A run with nothing
  of the output type produced answers an empty list without asking, whether its moves ran
  out or its budget did.
- A tool that fails with an `LLMReadableException` is a fact of the run: the failure's message
  goes on the turn and into the model's next state, the pair is spent, and the loop goes on;
  a correctable failure is logged as the tool's verdict, an uncorrectable one with its stack.
  Anything unreadable propagates as the system failure it is, and the run fails with it.
- The palette contract is a type, held by the compiler: every tool is a
  `DecisionTool<I, O>` (an artifact in, an artifact out), and the key tool, named apart in
  the constructor as `Class<? extends DecisionTool<?, ListArtifact<O>>>`, is the one whose
  output is a list of the answer type, so the answer can always be assembled. The thinker
  folds the key tool into the palette after the others, once. A palette is legal because it
  compiled; the constructor inspects no class, and `ClassToolProvider` is used only for what
  it does at run time (the tool's name and description, running it). Every tool has a
  `@ToolName` and the `(Identifiable parent)` constructor, as any tool a thinker instantiates
  by class does. Every constructor argument is required and the objective is not blank
  (`IllegalArgumentException` otherwise).
- The thinker holds no resources. Every decision is a `DecisionCall` and every tool run is a
  job, each submitted in the turn's iteration, so scope, guardrails, admission and the record
  apply to all of them unchanged. A subclass is itself a tool with the `(Identifiable parent)`
  constructor, so a decision thinker sits in another agent's palette like any tool.
- The run's record is `turns()`, complete once the run returns, and the same list as compact
  JSON under the job metadata key `OBS_TURNS` after every turn: with no reasoning to read, the
  distributions are what the model thought. Each turn is also reported as progress on the
  thinker's job, naming the move and the artifact it ran on.

## The contract

- The request is set before the requirements are captured; a call without one is refused
  there (`IllegalStateException`), since the requirements price the request
  (`DecisionCallTest`).
- The reservation is the wire body as counted under the resolved entry's tokenizer, with
  no output: a decision generates nothing. A state past the entry's ceiling fails at
  resolution, with the counts named, never on the wire (`DecisionResolutionTest`).
- The call's account is what the entry declares: one permit on the entry's gate when the
  entry is bounded by a concurrency (`max_concurrent`), a token reservation on its bucket
  when it is bounded by a quota window (`DecisionDemandTest`). `DecisionLoadTest` in the
  System One provider module pins the consequence: two hundred calls submitted at once all
  complete, the server never sees more than the entry's concurrency in flight, and nothing
  is dropped.
- Failures are the decision client's, classified for the dispatcher: a 429 and a 5xx or an
  unreachable server are paced and re-run within `Job.getUpstreamRetries()`, then refused
  naming the budget; a refusal no retry helps and a body that is not an answer surface
  as themselves. The job's timeout ends a call the server never answers.
- Every call, successful or failed, lands on the job's record with its answers or its
  reason, its billed input tokens, its latency and the served model, through
  `ObservableDecisionClient`, so a decision is priced, metered and traced like any other
  model call (`DecisionCallTest`).
- `DecisionThinkerTest` drives the loop through the dispatcher against the suite's fake
  decision model, whose policy the test installs: only legal moves are offered, a pair
  never twice, the input is never an answer, a readable failure is fed back and an
  unreadable one fails the run, the model's word and the turn budget both end a run, and
  every turn's distributions land on the thinker and on the job's record.
- A question id is the caller's handle, keying the wire request and its answers; reading an
  answer under an id asked another way, or never asked, is refused by name
  (`DecisionResponseTest`).
