# Package: ai.redouble.nucleo.ext.lit

This extension lets an agent read the biomedical literature. It gives a model tools that
search PubMed, the index of published biomedical papers, and bioRxiv and medRxiv, the servers
where biology and medicine preprints appear before review; tools that fetch an article's
details or, for open-access articles, its full text; and a ready-made agent that takes a
research topic and comes back with a synthesis and the papers behind it. It ships as the
artifact nucleo-ext-lit.

## Why the results are artifacts

An agent that cites papers has one job it must never get wrong: the reference. A model
passing citations from one step to the next is inclined to tidy them, and a tidied DOI or
PMID points at a different paper or at nothing. So every tool here returns each paper as a
`CitationArtifact`, the runtime's record of a citation
([Artifacts: data the model cannot alter](../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/artifacts/PACKAGE.md)).
The model reads the paper's title and abstract and decides whether it matters; it refers to
the paper by a reference such as `«artifact:link:cite~a1b2c3»`; the DOI, the PMID and the
author list reach your code exactly as PubMed or bioRxiv returned them.

A `CitationArtifact` carries the title and URL, the authors, the first author, the year, the
journal, the DOI, the abstract, the source and the relevance the model gave it. Each tool
returns its own subclass with the fields of its source: a PubMed article adds its PMID,
PMCID, publication types and MeSH terms (the subject headings PubMed indexes papers by), a
preprint its server, category and version.

## The tools

Hand them to an agent in its `declareDefaultTools()`, as any tool is handed over
([Tools, thinkers and doers](../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/tools/PACKAGE.md)).
All of them only read.

| The model calls | Class | What it does |
|---|---|---|
| `search_pubmed` | `PubMedSearchTool` | Searches PubMed and returns up to 100 articles (20 when the model asks for no number) with their metadata, and their abstracts when asked for. |
| `fetch_pubmed_articles` | `PubMedFetchTool` | Fetches up to 100 articles by PMID with the full record: abstracts, MeSH terms and keywords, and, when asked for, the authors' affiliations and the grants. |
| `fetch_pmc_fulltext` | `PMCFullTextTool` | Fetches the complete text of an article from PubMed Central by its PMCID. Only open-access articles have one. |
| `search_biorxiv` | `BioRxivSearchTool` | Searches bioRxiv and medRxiv preprints posted within a date range. |
| `fetch_biorxiv_preprint` | `BioRxivFetchTool` | Fetches one preprint by DOI with its authors, funding and publication status. |
| `literature_aggregator_agent` | `LiteratureAggregator` | The agent described below. |

Two things about the searches change what the model can ask:

- **PubMed's own query language reaches PubMed unchanged.** A model can write MeSH terms
  (`"neoplasms"[MeSH]`), `AND`, `OR` and `NOT`, date ranges (`2020/01/01:2024/12/31[dp]`)
  and publication types (`"Clinical Trial"[pt]`). A query PubMed rejects comes back to the
  model as an `InvalidInputException`, so it corrects the syntax and tries again
  ([Failures that behave](../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/errors/EXCEPTIONS.md)).
- **A bioRxiv search always names a date range.** The bioRxiv API lists every preprint posted
  within a window and has no keyword search, so the tool fetches the window and keeps the
  preprints whose title or abstract matches the query. A narrow window is a fast search.

## The literature agent

`LiteratureAggregator` is a thinker, an agent whose model decides which tool to call next
([The thinker families](../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/tools/thinking/PACKAGE.md)),
holding the five tools above and `CurrentTimeTool`. Give it a research topic, and optionally
a date range, a publication type and how many articles to analyze; its model chooses what to
search, what to fetch and what to read in full, and answers with a
`LiteratureAggregationOutput`:

- `summary`, the synthesis of what the literature says;
- `relevantArticles`, the papers it rests on, each a `CitationArtifact` with the model's note
  on why it matters;
- `keyThemes` and `researchGaps`, the themes it found across the papers and what the
  research leaves open;
- `articlesAnalyzed`, how many articles it read to write this.

It runs at grade XL, for at most 20 turns. Because a thinker is itself a tool, a larger agent
can hold it like any other: list `LiteratureAggregator.class` in that agent's
`declareDefaultTools()`, and the whole literature review runs as one tool call of the parent,
whose citations reach the parent as the same artifacts.

## The credential

The tools need no credential, and run better with one. NCBI, which runs PubMed and PubMed
Central, allows ten requests a second to a caller with an API key and three to one without,
and may block a caller that sends more. Register a key in your NCBI account settings and
store it in the deployment's credential store under the id `ncbi-api-key`
(`PubMedRateLimiter.NCBI_SECRET_ID`); with the shipped store, that is the environment
variable `NCBI_API_KEY` ([Credentials](../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/secrets/PACKAGE.md)).
Without one, the PubMed tools log a warning naming what to provide and run unauthenticated.
bioRxiv needs no key.

Every PubMed and PubMed Central call shares one limit of ten a second, held by the runtime's
admission before the request is sent
([Admission](../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/admission/PACKAGE.md)),
so every agent in the process searching at once shares that one limit, with no code of
yours.

## Offering them over MCP

`LiteratureAggregator`, `PubMedSearchTool` and `PMCFullTextTool` carry `@MCP`, which makes
them available to a Nucleo MCP server for outside clients
([Serving tools over MCP](../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/mcp/server/PACKAGE.md)).
The host's access policy then decides, per client, which of them that client may call.

References: [E-utilities](https://www.ncbi.nlm.nih.gov/books/NBK25501/),
[PubMed search syntax](https://pubmed.ncbi.nlm.nih.gov/help/),
[MeSH browser](https://www.ncbi.nlm.nih.gov/mesh/), [bioRxiv API](https://api.biorxiv.org/),
[NCBI account settings](https://www.ncbi.nlm.nih.gov/account/settings/).

## How it works inside

How the tools map onto the NCBI and bioRxiv endpoints, how the rate limit is held, and the
rules for writing a new tool here are in
[Inside literature search](EXT_LIT_INTERNALS.md), for those working on the extension itself.
