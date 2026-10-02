# Package: ai.redouble.demo.decide

This is the agent step 8 of the demo runs: an agent whose model is a decision model, a model
that writes no text and answers typed questions about a state with a probability for each
option ([What a decision model is](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/tools/deciding/PACKAGE.md)).
It shows that a whole agent can run on such a model: every choice the run makes is a
decision, and everything that reaches the answer was produced by code.

## What it does

`PriceChangeFinder` is given a folder and one objective: find every statement in the folder's
documents that decides a change of a price and says from when the new price applies. It is a
`DecisionThinker`, the runtime's agent loop for a decision model, and it works with three
tools, none of which calls a model:

| Tool | Takes | Produces |
|---|---|---|
| `list_folder` (`ListFolderTool`) | the folder | a list of its files, each with its kind (read from its bytes), its size and its first words |
| `read_document` (`ReadDocumentTool`) | one file | its text: a text file decoded, an Office document with its markup stripped. A PDF, an image, a binary or an empty file is refused, and the refusal is shown to the model as a fact of the run. |
| `split_statements` (`SplitStatementsTool`) | a document | its statements: each sentence, each bullet or numbered item |

Each tool takes an artifact, a piece of data the runtime keeps unaltered
([Artifacts: data the model cannot alter](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/artifacts/PACKAGE.md)),
and produces one. The answer is a list of the `Statement`s `split_statements` produced, each
with the document it came from and its place in it.

## A turn

Every turn the model is shown the run's state: the objective, one line for each artifact so
far, and the moves already made. The line is a short digest, such as a file's name, kind,
size and first words, and it is all the model sees of the artifact. Against that state it
answers three kinds of question at once: which tool should run next, or whether to finish,
which is offered once a statement exists; on which artifact each offered tool should run,
among those of the tool's input type it has not yet run on; and, for every statement produced
so far, whether it belongs in the answer. When finish wins, the statements it put in the
answer are the result.

The model decides which file to open, which document to split, when to stop and which
statements answer the objective. It never writes a value, so nothing in the answer can be
misquoted.

## What a run looks like

On the shipped folder, Kev-9B served from a Mac (2026-09-24, run twice with the same record
both times) took four turns in 22 to 26 seconds, read 11,000 tokens and cost nothing. It
opened the dealer portal changelog, the one file of about thirty whose first words name a
price (chosen at 0.21 and 0.26), split it, finished at 0.68 and 0.60 over reading on, and
selected the changelog's April line about the Kestrel 1 prices at 0.92, with every other
statement under 0.26. The minutes and the meeting notes, whose first words name a meeting,
ranked below it and were never opened: the model's decision, on the digests it was given, and
the record shows it.

The run makes no model call but the decisions, so on a decision model served from your own
machine it costs nothing; on a hosted one each turn reads a few thousand input tokens, more as
the state grows. Every decision is priced, admitted and recorded like any model call. The
decision model is the catalog's `decision` entry, else the first decision entry the connected
endpoint serves ([Connecting a decision model](../../../../../../../../nucleo-provider-systemone/src/main/java/ai/redouble/nucleo/providers/systemone/PACKAGE.md)).

The order of things is fixed: the state's lines and every question's options are in the
order the artifacts came to be, the same on every run, because a decision model is sensitive
to the order of options and a run's record must repeat.

## How it works inside

The classes, the digests, the stream the page draws the run from and the tests that pin it
are in [Inside the decision agent](DEMO_DECIDE_INTERNALS.md), for those working on the demo
itself.
