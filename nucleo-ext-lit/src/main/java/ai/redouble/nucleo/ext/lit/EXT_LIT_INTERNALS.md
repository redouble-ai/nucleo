# Inside literature search

This page is for people working on the literature extension itself: how its tools map onto
the NCBI and bioRxiv endpoints, how the rate limit is held, and the rules a new tool here
follows. What the tools do for an agent is in [Literature search](PACKAGE.md).

## Philosophy

PubMed returns 36M+ records. Passing citations through multi-agent systems risks DOI corruption, PMID confusion, and author-name mangling because the LLM has every incentive to "helpfully" normalise identifiers. This package solves citation integrity by returning `CitationArtifact` (from the artifacts framework, see `harness/artifacts/PACKAGE.md` in nucleo-core) instead of free-text citations. The LLM reasons about relevance from the registered reference `«artifact:link:cite~...»` but never touches the raw DOI / PMID / author strings.

## Architecture

Each tool is a 1:1 wrapper over one NCBI or bioRxiv endpoint; `LiteratureAggregator` is a `SingleObjectiveThinker` composed on top of those tools whose model runs the research loop and returns selected citations with explanations.

```
[LLM] -> LiteratureAggregator (Thinker) -+-- PubMedSearchTool  --+--> PubMedRateLimiter (10 QPS)
                                          +-- PubMedFetchTool    |     via NCBI E-utilities
                                          +-- PMCFullTextTool   --+
                                          +-- BioRxivSearchTool  \
                                          +-- BioRxivFetchTool     \--- bioRxiv API (date-window only; keyword filter in memory)
                                          +-- CurrentTimeTool
```

### Which one do I pick?

| Need | Use |
|------|-----|
| Peer-reviewed biomedical literature (MeSH, pub types, date ranges) | `PubMedSearchTool` + `PubMedFetchTool` |
| The full text of an article, not just its abstract | `PMCFullTextTool` - open-access articles in PubMed Central only; abstracts come from `PubMedFetchTool` |
| Biology / medicine preprints | `BioRxivSearchTool` + `BioRxivFetchTool` |
| Autonomous topic research with synthesis, gap analysis, and theme extraction | `LiteratureAggregator` |
| Compose citations into a higher-level agent | Declare `LiteratureAggregator.class` in that agent's `declareDefaultTools()` - it's a Thinker, so it runs its own tool loop as a single tool call from the parent |

Start reading at `LiteratureAggregator` for the agent loop, then `PubMedFetchTool` for the canonical fetch pattern, then `CitationArtifact` for the identifier-safe result shape.

## Invariants and how-to

### Citations never travel as strings

Every tool returns its papers as subclasses of `CitationArtifact` (`@TypeAlias("link:cite")`): `PubMedSearchOutput.PubMedArticle` (`link:cite:pubmed`), `PubMedFetchOutput.DetailedArticle` (`link:cite:pubmed:full`), `PMCArticle` (`link:cite:pmc`), `BioRxivSearchOutput.BioRxivPreprint` (`link:cite:biorxiv`) and `BioRxivFetchOutput.DetailedPreprint` (`link:cite:biorxiv:full`). The base artifact carries `doi`, `authors`, `firstAuthor`, `journal`, `year`, `abstractText`, `source` and `relevance` plus `title` / `url` inherited from `LinkArtifact`; the PMID and PMCID live on the PubMed and PMC subclasses. Aggregators and downstream agents see the registry reference, not the fields. If a tool is tempted to emit a formatted string like "Author et al., NEJM 2023", stop - use the artifact instead and let the renderer format at the edge.

### Rate limit via the singleton

`PubMedRateLimiter` enforces NCBI's 10 QPS cap with a sliding window. A tool books one
slot per job and admission holds it for the job's duration, like a pooled connection:
`PubMedSearchTool`'s two sequential API calls (ESearch then EFetch) ride the one held
slot, so the account admits ten concurrent searches. Access it the canonical way:

```java
RateLimiterFactory.getInstance().getRateLimiter(PubMedRateLimiter.class)
```

Without an `ncbi-api-key`, NCBI silently downgrades callers to 3 QPS and may block - register a key at https://www.ncbi.nlm.nih.gov/account/settings/ and store it in the deployment's `Secrets` store under `PubMedRateLimiter.NCBI_SECRET_ID` (`ncbi-api-key`).

### PubMed query syntax is passed through

PubMed's full search grammar (MeSH terms like `"neoplasms"[MeSH]`, boolean `AND`/`OR`/`NOT`, date ranges `2020/01/01:2024/12/31[dp]`, publication types `"Clinical Trial"[pt]`) is supported by the raw `PubMedSearchTool` because we just forward the `term` parameter. No input validation: if NCBI rejects it with HTTP 400, the tool throws `InvalidInputException` and the LLM can retry with corrected syntax.

### bioRxiv is a date-window API

The bioRxiv API exposes a date-window endpoint only, with no keyword search. `BioRxivSearchTool` therefore requires both a query and a bounded `dateFrom`/`dateTo` range, fetches every preprint in the window, and filters by keyword in memory against titles and abstracts. HTML scraping of the public site is prohibited by bioRxiv's text-and-data-mining policy and is not performed.

### LiteratureAggregator as a sub-agent

`LiteratureAggregator` extends `SingleObjectiveThinker` which extends `Tool`, so any higher-level agent can declare it in `declareDefaultTools()` and invoke it with typed input. The aggregator runs its own internal tool loop, its model deciding what to search, fetch and read, and returns one `LiteratureAggregationOutput` to the caller. Output fields: `summary`, `relevantArticles` (`List<CitationArtifact>` with per-article relevance), `articlesAnalyzed`, `keyThemes`, `researchGaps`.

## Configuration

- `ncbi-api-key` in the deployment's `Secrets` store (optional but strongly recommended).
- References: [E-utilities](https://www.ncbi.nlm.nih.gov/books/NBK25501/), [PubMed search syntax](https://pubmed.ncbi.nlm.nih.gov/help/), [MeSH browser](https://www.ncbi.nlm.nih.gov/mesh/), [bioRxiv API](https://api.biorxiv.org/).

## MCP exposure

Three tools in `tools` carry `@MCP` and are exposable to external MCP clients through
`ai.redouble.nucleo.mcp.server`: `LiteratureAggregator`, `PubMedSearchTool`, and
`PMCFullTextTool`. Exposable is not exposed; the host's access policy decides per
consumer.
