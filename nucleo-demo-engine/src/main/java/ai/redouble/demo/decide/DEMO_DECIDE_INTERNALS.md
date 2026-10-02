# Inside the decision agent

This page is for people working on the demo's decision agent itself: its classes, the stream
the page draws it from, and the tests that pin it. What the agent does and what a run looks
like are in [The decision agent](PACKAGE.md).

The runtime's `tools.deciding.DecisionThinker` is the loop; this package is the workload it
runs, the artifacts and tools of that workload, and the stream a page draws it from.

## What is here

| Class | Role |
|---|---|
| `PriceChangeFinder` | The agent: a `DecisionThinker<Folder, Statement>` whose objective is to find the statements in a folder's documents that decide a change of a price, over a palette of three `DecisionTool`s that call no model, `SplitStatementsTool` being the key tool (the one that produces the statements the answer is a list of). The palette is legal because it compiled. `capabilities()` is the objective and the palette for a page. |
| `Folder`, `FileEntry`, `Document`, `Statement` | The artifacts, each with a `@TypeAlias`: the folder a run starts from; a file as the listing saw it (name, kind by its bytes, size, first words); a file read as text; one statement of a document with its source and place. |
| `ListFolderTool` (`list_folder`) | `Folder` to a list of `FileEntry`: every regular file, by name, sniffed the extract step's way (`Sniff`), a text file's first words decoded; a path that is not a folder refused with the reason (`InvalidInputException`). |
| `ReadDocumentTool` (`read_document`) | `FileEntry` to `Document`: a text file decoded (refused as not text past 5 percent undecodable characters), an Office document with its tags stripped (`DeterministicExtractTool.officeText`); a PDF, an image, a binary or an empty file refused with the reason (`InvalidInputException`), which the thinker feeds back to the model as a fact of the run. |
| `SplitStatementsTool` (`split_statements`) | `Document` to a list of `Statement`: a paragraph's sentences, each bullet or numbered item; headings dropped, fragments under three words dropped. |
| `Digests` | What the model reads of each artifact type, registered as the types' text formatters: a decision thinker's state is one digest line per artifact and nothing else of it, so these lines are what the model decides on. A file reads as `name (kind, size): first words`, a document as `name (N characters): first words`, a statement as `source #n: text`. |
| `DecisionTrace` | The run streamed as newline-delimited JSON to any sink: every event of the thinker and of every job under it, a decision's completion carrying the whole exchange (the state shown, the questions, every distribution answered, the call's account), a tool's completion carrying what it produced (ref and digest, a list's iterands the same way), and a last line with the statements selected, the thinker's recorded turns and the totals, or the failure. Ends the stream from the run's own terminal event and tells the host; `awaitEnd` and `fail` are for the handle's holder, as with the agent's trace. |
| `DecideCapabilities`, `DecideRequest` | The palette as a page shows it (`PriceChangeFinder.capabilities()`: the objective, the input and answer aliases, and per tool its name, description, and the aliases it takes and produces, `list` for a list); a run's request (the folder). |

## The tests

`PriceChangeFinderTest` drives the whole run through the dispatcher against the runtime's
fake decision provider on a scripted policy that opens the two meeting documents, splits them
and selects the two statements that decide a price (the 20 percent rise from 1 June 2026, the
Kestrel 1 prices effective 1 April 2026), and reads the stream back; it also pins that a sink
which loses its reader stops the lines and not the run, and what `capabilities()` reports.
`DecideToolsTest` pins the three tools and the digests on the corpus.
