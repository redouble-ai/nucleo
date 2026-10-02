# Package: ai.redouble.nucleo.ext.patent.usptoodp

This package lets an agent research US patents through the USPTO Open Data Portal (ODP), the
United States Patent and Trademark Office's public API. Besides search and the patent text,
ODP carries what happens to an application at the patent office, data the EPO does not
publish: the prosecution history (every action taken on the application), the continuity
chain (the parent and child applications a patent descends from and gave rise to) and the
foreign priority claims. One more agent holds every tool here and answers a US patent
question on its own. It ships in the artifact nucleo-ext-patent, beside the
[EPO](../epo/PACKAGE.md) tools, which cover patents outside the US.

## The tools

Hand them to an agent in its `declareDefaultTools()`
([Tools, thinkers and doers](../../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/tools/PACKAGE.md)).
All of them only read. The ones that work on one patent take it as its plain number
(`10757852`).

| The model calls | Class | What it returns |
|---|---|---|
| `uspto_odp_search` | `UsptoOdpSearchTool` | Granted US patents matching a title, an assignee, an inventor's last name, a CPC class or a filing-date range, as `PatentArtifact`s, newest grant first; with `includeApplications` set, pending applications too. 25 results unless the model asks for up to 100. |
| `uspto_odp_fetch` | `UsptoOdpFetchTool` | One patent's front page as a `PatentArtifact`, with its abstract when ODP holds the patent's grant document. |
| `uspto_odp_fulltext` | `UsptoOdpFullTextTool` | The front page, the abstract, the claims and the description in one `PatentFullContentArtifact`. It downloads the patent's whole grant document, so it is the expensive one. |
| `uspto_odp_legal_events` | `UsptoOdpLegalEventsTool` | The application's status and its prosecution events, dated, in order. |
| `uspto_odp_continuity` | `UsptoOdpContinuityTool` | The parent and child applications of the patent. |
| `uspto_odp_foreign_priority` | `UsptoOdpForeignPriorityTool` | The foreign priority claims and the earliest priority date. |

The legal-events, continuity and foreign-priority tools take an application number as well
as a patent number. ODP keeps that data by application, so given only a patent number these
tools first look up its application, one extra request; a model that already holds the
application number saves it.

A search that matches nothing returns no patents. A patent number ODP does not know is a
`ResourceNotFoundException`, which tells the model that number does not exist
([Failures that behave](../../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/errors/EXCEPTIONS.md)).

## The patent agent

`UsptoOdpThinker`, called `uspto_odp` by a model, is a thinker, an agent whose model decides
which tool to call next ([The thinker families](../../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/tools/thinking/PACKAGE.md)).
It holds the six tools above and `CurrentTimeTool`, runs at grade MEDIUM for at most ten
turns, and is told to search first, fetch the front page of each match, and download full
text only when the question is about claims or description. It answers a
`UsptoOdpThinkerOutput`: a `summary`, the `patents` as `PatentArtifact`s and `patentsFound`.
`maxPatents` on its input caps how many it returns, five unless set.

## The credential

Register at [data.uspto.gov](https://data.uspto.gov) for an API key and store it in the
deployment's credential store under the id `myodp-api-key` (`UsptoOdpClient.SECRET_ID`); with
the shipped store, that is the environment variable `MYODP_API_KEY`
([Credentials](../../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/secrets/PACKAGE.md)).
Without it, the client logs a warning naming what to provide when it is first loaded, and the
tools send their requests unsigned and fail as unauthorized.

## One request at a time

ODP serves one request at a time per API key and refuses a second one sent while the first is
running. So every tool here runs under one shared limit that admits a single tool at a time,
about four a second, and holds it while the tool makes all of its requests
([Admission](../../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/admission/PACKAGE.md)).
Many agents can use the tools at once; their ODP calls queue behind one another in the runtime
instead of being refused by ODP.

## How it works inside

The endpoints each tool calls, the two meanings ODP gives a 404, the shape of a search request,
the three steps of a full-text download and how to add an endpoint are in
[Inside the USPTO ODP tools](EXT_PATENT_USPTOODP_INTERNALS.md), for those working on the
extension itself.
