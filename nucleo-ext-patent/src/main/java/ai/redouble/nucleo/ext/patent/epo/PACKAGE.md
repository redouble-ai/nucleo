# ai.redouble.nucleo.ext.patent.epo

This package lets an agent research patents through the European Patent Office's Open Patent
Services (OPS), the EPO's public API over its worldwide patent data. Its tools search published
patents, fetch a patent's front page, claims and description, follow its family across
countries, read its legal status and find its PDF; one more agent holds them all and answers a
patent question on its own. It ships in the artifact nucleo-ext-patent, beside the
[USPTO Open Data Portal](../usptoodp/PACKAGE.md) tools for US patents.

## The tools

Hand them to an agent in its `declareDefaultTools()`
([Tools, thinkers and doers](../../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/tools/PACKAGE.md)).
All of them only read.

| The model calls | Class | What it returns |
|---|---|---|
| `epo_search` | `EPOSearchTool` | The numbers of the patents matching a query: by title, title and abstract, applicant, inventor, number, IPC or CPC class and publication date, or a raw CQL query (the OPS query language). 25 results unless the model asks for up to 100. |
| `epo_biblio` | `EPOBiblioTool` | A patent's front page as a `PatentArtifact`: title, abstract, applicants, inventors, dates, application number, classifications, and a link to the patent on Espacenet. |
| `epo_claims` | `EPOClaimsTool` | The claims, as text. |
| `epo_description` | `EPODescriptionTool` | The description, the specification's body, as text. |
| `epo_fulltext` | `EPOFullTextTool` | Claims and description in one `PatentFullContentArtifact`. It makes three calls to EPO; the claims or the description the patent lacks is left empty. |
| `epo_family` | `EPOFamilyTool` | The patent's INPADOC family: the related publications in every country, and the family id. |
| `epo_legal_status` | `EPOLegalStatusTool` | The current legal status and the legal events in order. |
| `epo_images` | `EPOImagesTool` | The URL of the patent's PDF and its page count, for a document-parsing tool to read. |
| `epo_number_convert` | `EPONumberConversionTool` | A patent number converted between EPO's two number formats. |

Claims, descriptions and abstracts can run long, so they are marked `@LLMSummarizable`: the
model is shown a summary and your code keeps the whole text
([Answers as Java objects](../../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/schema/PACKAGE.md)).

## Patent numbers

EPO writes a patent number two ways: DOCDB, dotted (`EP.1000000.B1`), and EPODOC,
concatenated (`EP1000000B1`). Every tool takes DOCDB unless told `inputFormat = epodoc`, and
every number a tool reads out of EPO's data it prints in DOCDB: search results, family
members, the front page's patent and application numbers. So a model can take any number one
tool found and hand it to any other tool unchanged. The description, full-text and images
tools repeat the number they were given, as it was given. Two numbers are written undotted on
purpose: the one `epo_number_convert` is asked to produce in EPODOC, and the Espacenet link,
whose query syntax takes the number that way.

## The patent agent

`EPOThinker`, called `epo` by a model, is a thinker, an agent whose model decides which tool
to call next ([The thinker families](../../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/tools/thinking/PACKAGE.md)).
It holds the nine tools above and `CurrentTimeTool`, runs at grade MEDIUM for at most ten
turns, and answers an `EPOThinkerOutput`: a `summary` of what it found, the `patents` as
`PatentArtifact`s, and `patentsFound`, how many matched. `maxPatents` on its input caps how
many it returns, five unless set. A larger agent holds it as one tool, the way it holds any
other.

## The credential

OPS requires an account. Register at [developers.epo.org](https://developers.epo.org) and
you receive a consumer key and a consumer secret. Store them in the deployment's credential
store under the id `epo-api-key` (`EPOClient.SECRET_ID`), the consumer key as the credential's
user and the consumer secret as its secret; with the shipped store, those are the environment
variables `EPO_API_KEY_USER` and `EPO_API_KEY`
([Credentials](../../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/secrets/PACKAGE.md)).
Without it, the tools log a warning naming what to provide, and every call fails with an
`UnauthorizedException` that says the same.

## Rate limits

OPS limits each of its services separately (search, retrieval, the INPADOC family and legal
data, images, and everything else) and says in every response how close each one is to its
limit. The runtime holds one limit per service, sized to OPS's free tier, and admits every
call against the limit of the service it uses before it is sent
([Admission](../../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/admission/PACKAGE.md)),
slowing a service down as soon as OPS reports it busy. A search that runs into EPO's limit
leaves the fetches running. None of it needs code of yours.

## Offering it over MCP

`EPOSearchTool` carries `@MCP`, which makes it available to a Nucleo MCP server for outside
clients ([Serving tools over MCP](../../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/mcp/server/PACKAGE.md));
the host's access policy decides, per client, whether that client may call it.

## How it works inside

How the client handles OPS's authentication, its throttling signals and its XML, how the
per-service limits route, and how to add an endpoint are in
[Inside the EPO tools](EXT_PATENT_EPO_INTERNALS.md), for those working on the extension itself.
