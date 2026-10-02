# Package: ai.redouble.nucleo.tools.deciding

Much of what an application asks a chat model is a verdict: which team should take this
ticket, is it urgent, are these two product names the same product. The chat model writes
its verdict as text, and the code then parses the text, checks it names one of the allowed
answers, and hopes the model's confidence is somewhere in the words.

A decision model answers a verdict directly. This page says what a decision model is, what
it is good and bad at beside the chat models the rest of the runtime drives, how to ask it
one question (`DecisionCall`), and how to run a whole agent on one (`DecisionThinker`).

## What a decision model is

A chat model (an LLM) reads text and writes text: an answer, a tool call, a JSON object,
a plan. A decision model reads text and writes nothing. You hand it a state (the text or
structure to judge) and a set of typed questions about that state, and it answers each
question with a probability distribution over options you declared:

| Question | You give | You get back |
|---|---|---|
| `Choice` | the options, by key, each with an optional description | a probability per option, the winner, and a confidence figure |
| `Noul` | a statement that is true or false | one number: the probability that it is true |
| `Score` | an ordered scale, lowest level first | a probability per level and the weighted position on the scale |

That is the whole interface. The model cannot name a tool, fill an argument, invent an
option, quote the state or explain itself. Every question in a request is answered
independently against the one state, in one round trip, and the endpoint bills the state
once; output is free. The shape is TypeSafe's Jev's, and the open replicas served from a
machine the deployment owns (Kev, Nimble, OpenJev) speak the same wire, so one provider
module (`nucleo-provider-systemone`) serves them all.

## Decision model or LLM: when to use which

Use a decision model where the answer is a verdict over options code can enumerate, and an
LLM where the answer has to be written.

| The work | Reach for | Why |
|---|---|---|
| Route a ticket to one of five teams; is this urgent; how severe | decision model | a choice, a noul, a score: the options are yours, the answer is a distribution you threshold in code |
| Are these two names the same product; does this sentence decide a price; does this item belong in the answer | decision model | one verdict per pair or per item, hundreds of them in one round trip, each a calibrated number |
| Which of these files is worth opening; which tool should run next; should the run stop | decision model | a ranking of moves code computed; the model never invents a move that does not exist |
| Extract the prices a document states, with their dates and the sentence they came from | LLM | the answer is text and structure the model writes; a decision model cannot write a value |
| Read a 40-page contract and summarize the termination terms | LLM | a summary is written text, and a decision model reads a bounded state and answers only what you asked |
| Call a tool with arguments; write a query; draft a notice | LLM | a decision model has no way to name anything that is not an option you gave it |
| Add up the invoice, apply the 20 percent from June, compare with the list price | plain Java | neither model does arithmetic reliably; code does, and it is testable |

What a decision model is good at:

- **Nothing it produces can be malformed or invented.** There is no JSON to parse and no
  correction loop. What leaves a run is what your tools produced, byte for byte; the model
  only chose among it.
- **Every answer is a number you threshold in code.** A yes at 0.92 and a yes at 0.51 are
  different facts, and the threshold is yours (`DecideProductGroupsTool.THRESHOLD` in the
  demo, `setSelectionThreshold` on the thinker), a number in code where an LLM would need a
  phrase in a prompt.
- **Many questions cost one state.** A request carries every question at once; a dozen
  names are sixty-six pair verdicts in one call, and the endpoint bills the state once.
- **Cheap.** TypeSafe's hosted Jev lists at $0.042 per million input tokens and nothing for
  output; Kev on your own machine costs nothing per call.
- **The record is complete.** With no reasoning text, the distributions are what the
  model thought, and every one of them is on the job's record.

What it is bad at, and how to work with it:

- **It does not read much.** The state ceiling is the entry's `max_context_tokens` (16,384
  on Kev, 64,000 on Jev), and the model answers a thin state far better than a thick one.
  The thinker shows it one digest line per artifact, never a document's text; a judgment
  that needs the full text belongs inside a tool, which may be a `DecisionCall` on that one
  document.
- **It writes nothing.** Extraction, drafting, querying, tool arguments: an LLM's work.
  In a pipeline, the decision model judges what an LLM or code produced.
- **A probability is the model's belief.** The number is what the endpoint reported, and no
  published calibration says how often it is right, so it is a value to threshold and to
  compare, and never a measured rate of being right.
- **Option order moves the answer.** The runtime keeps every list in the order it was
  asked, on the wire and in the thinker's world, so a run's record repeats; keep your
  options in a `LinkedHashMap`.
- **It cannot enforce an invariant across questions.** Questions in one request do not
  see each other's answers, so a statement and its negation do not sum to one; code
  enforces that, and a choice usually carries a `none` option for a state that answers
  nothing.

## One decision: `DecisionCall`

A decision is a job like any model call: it is priced, admitted against the model's limits
and recorded. Build the request, submit the call, read the answers by the shape you asked:

```java
LinkedHashMap<String, Question> questions = new LinkedHashMap<>();
questions.put("department", Choice.of("Which team should handle this ticket?", "billing", "technical", "none"));
questions.put("is_urgent", Noul.of("Does the ticket convey urgency?"));
questions.put("mood", Score.of("How is the customer?", "calm", "frustrated", "very angry"));
DecisionRequest request = new DecisionRequest(ticketText, questions);
DecisionCall call = new DecisionCall(Job.workflow("support", "triage"), request);
DecisionResponse response = JobDispatcher.getInstance().submit(call).get();
String team = response.choice("department").choice();
double urgent = response.noul("is_urgent").probability();
int mood = response.score("mood").topLevel();
```

The question ids (`department`, `is_urgent`, `mood`) are yours: they key the request and
its answers, and the model is asked each question's instructions, nothing about the id.
The state may be a string or a structure (a map, a list, a plain object), sent as JSON. The
call is served by the deployment's decision model, the `decision` pin of its catalog
([Grades, the catalog and the picker](../../harness/models/PACKAGE.md)); `pinModel(spec)`
names one entry instead. The questions and answers themselves are described in
[The decision wire](../../harness/decision/PACKAGE.md).

A tool that already runs as a job asks the same way from inside: it declares the decision
model in its requirements with `JobRequirements.requireDecision`, and in `execute` calls
`resources.getDecisionClient(binding.getModel()).decide(request)`, as the demo's
`DecideProductGroupsTool` does.

`DecisionCall` is deliberately not a tool a chat model can call: it has no tool name. A
chat model asking a decision model is a design a caller makes on purpose, by wrapping the
call in a tool of its own.

## An agent on a decision model: `DecisionThinker`

A decision model cannot call a tool or name an argument: it only ranks options it is given.
So in a `DecisionThinker`, code works out the options and the model ranks them. Everything
the run holds is an artifact (the data a tool produced, handed around unaltered:
[Artifacts: data the model cannot alter](../../harness/artifacts/PACKAGE.md)), and every
tool takes one artifact and produces one. A move is a tool applied to an artifact of the
type it takes, or `finish`. Each turn:

1. Code lists the legal moves: every tool that has an artifact of its input type to run
   on, each pair of tool and artifact at most once, and `finish` once some tool has
   produced an artifact of the answer's type.
2. The model reads a short state: the objective, one line per artifact, and the moves made
   so far with their outcomes. In one round trip it ranks the moves, ranks the candidate
   artifacts for each tool, and says for every artifact of the answer's type whether it
   belongs in the answer.
3. Code runs the winning move and adds what it produced to the run.

The answer is the artifacts of the answer's type whose "belongs in the answer" probability
reaches the selection threshold, 0.5 unless `setSelectionThreshold` says otherwise. A run
ends when the model chooses `finish`, when no move is left, or after `setMaxTurns` turns (32
by default), and `turns()` is its record: every distribution the model answered, turn by
turn.

### Writing one

Three artifact types, two tools, one thinker. The model decides which note to split, which
part to judge, when to stop, and which verdicts answer; the tools do the work.

```java
@TypeAlias("note")
public class Note extends AbstractArtifact {
    @LLMDescription("The note's text")
    private String text;
    public Note() {}
    public Note(String text) { this.text = text; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
}

@TypeAlias("verdict")
public class Verdict extends AbstractArtifact {
    @LLMDescription("The note judged")
    private String about;
    @LLMDescription("Whether the note is worth keeping")
    private Boolean keep;
    public Verdict() {}
    public Verdict(String about, boolean keep) { this.about = about; this.keep = keep; }
    public String getAbout() { return about; }
    public void setAbout(String about) { this.about = about; }
    public Boolean getKeep() { return keep; }
    public void setKeep(Boolean keep) { this.keep = keep; }
}

@ToolName("split_note")
@ToolDescription(value = "Splits a note into its parts", readOnly = true)
public class SplitNote extends DecisionTool<Note, ListArtifact<Note>> {
    public SplitNote(Identifiable parent) { super(parent); }

    @Override
    public ListArtifact<Note> execute(JobResources resources, JobContext<ListArtifact<Note>> context) {
        List<Note> parts = new ArrayList<>();
        for (String part : input.getText().split(";")) {
            parts.add(new Note(part.trim()));
        }
        ListArtifact<Note> list = new ListArtifact<>();
        list.setIterands(parts);
        list.setIterandTypeAlias("note");
        return list;
    }
}

/** The key tool: it produces the verdicts the answer is a list of, one per note. */
@ToolName("judge_note")
@ToolDescription(value = "Judges whether a note is worth keeping", readOnly = true)
public class JudgeNote extends DecisionTool<Note, ListArtifact<Verdict>> {
    public JudgeNote(Identifiable parent) { super(parent); }

    @Override
    public ListArtifact<Verdict> execute(JobResources resources, JobContext<ListArtifact<Verdict>> context) {
        ListArtifact<Verdict> verdicts = new ListArtifact<>();
        verdicts.setIterands(List.of(new Verdict(input.getText(), input.getText().contains("alpha"))));
        verdicts.setIterandTypeAlias("verdict");
        return verdicts;
    }
}

@ToolName("triage")
@ToolDescription(value = "Keeps the verdicts on the parts of a note worth keeping", readOnly = true)
public class Triage extends DecisionThinker<Note, Verdict> {
    public Triage(Identifiable parent) {
        super(parent, Verdict.class, JudgeNote.class, List.of(SplitNote.class),
                "Judge every part of the note and keep the verdicts on the parts worth keeping");
    }
}

Triage triage = new Triage(Job.workflow("inbox", "triage"));
triage.setInput(new Note("alpha is due; beta can wait"));
ListArtifact<Verdict> kept = JobDispatcher.getInstance().submit(triage).get();
List<DecisionTurn> record = triage.turns();
```

`DecisionTool<I, O>` is the base of every tool a decision thinker holds: an ordinary tool
whose input and output are both artifacts. A thinker names three things in its
constructor: the answer's type, the **key tool**, whose output is a list of that type (here
`JudgeNote`, so an answer can always be assembled), and the other tools. A palette that
breaks this does not compile. A decision thinker is itself a tool, so it can sit in another
agent's palette like any other.

On the first turn the model is offered `split_note` and `judge_note`, each on the whole
note; `finish` is not offered, since no verdict exists. Once the note is split, its parts
are candidates by their own type and the whole note is still a candidate for the judge;
once a verdict exists, `finish` is offered and every verdict is put to the model as a
selection.

### Designing a palette

- **A tool is a function of one artifact.** The model chooses the tool and the artifact;
  everything else the tool needs it carries itself or reads from the artifact.
- **Give every type a digest.** The one line per artifact is all the model sees of it.
  Register an `ArtifactTextFormatter` for each artifact type the model will see
  (`TextFormatterRegistry.register`), one line that says what a person would glance at: a
  file's name, kind, size and first words; a statement's source and text. The default (the
  artifact's summarized JSON) works, and a line you wrote decides better.
- **Keep the state thin.** A document's text never goes into the state; a tool that judges
  a document's contents does so inside itself, and may be a `DecisionCall` on the document
  alone. The thinker's state is the objective, the digests and the history, and that is what
  fits the model.
- **Let a tool refuse.** A tool that cannot take an artifact throws an
  `InvalidInputException` (or any `LLMReadableException`) with the reason; the model reads
  the reason next turn and that pair is never offered again. A bug throws anything else and
  fails the run.
- **The key tool produces the answer type.** Name it apart in the constructor and the
  compiler holds the rest of the contract.

## Connecting a decision model

A decision entry sits in the deployment's `models.json` beside the chat and embeddings
entries. It has no grade, since decision models are not on the ladder, and it states one
limit: tokens and requests per minute for a hosted endpoint, or `max_concurrent`, how many
requests at once, for a server on a machine the deployment owns. The deployment names the
entry that answers decisions under `"pins": {"decision": "<id>"}`; without a pin, the first
decision entry the deployment can call whose endpoint serves it answers, and the log says
so. The provider, its two connections and its shipped entries are in
[Connecting a decision model](../../../../../../../../../nucleo-provider-systemone/src/main/java/ai/redouble/nucleo/providers/systemone/PACKAGE.md).

## How it works inside

The classes, the exact questions a thinker's turn asks and the state it shows, and the
contract the tests pin are in
[Inside decision calls and the decision thinker](TOOLS_DECIDING_INTERNALS.md), for those
working on the runtime itself.

> **Example:** [One decision](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/decision/PACKAGE.md) -
> a support ticket as a choice, a yes-or-no and a score, answered by a local Kev with the
> probability of every option.
