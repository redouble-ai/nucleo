# Inside the USPTO ODP tools

This page is for people working on the USPTO Open Data Portal extension itself: the endpoints
each tool calls, the client methods they share, and how to add an endpoint. What the tools do
for an agent, and the credential they need, are in [Patents: USPTO ODP](PACKAGE.md).

USPTO Open Data Portal (ODP) integration - the US-only sibling of `patent/epo/`; this file documents what is specific to ODP.

## Philosophy

ODP is a newer REST API that replaced USPTO's legacy PAIR/PatFT endpoints. Its shape is opensearch-style: one POST search endpoint with a query DSL, plus per-application subpath fetches. Unlike EPO OPS, there is no OAuth dance - a single `X-API-KEY` header authenticates everything.

The surface area is wider than EPO's because the ODP API exposes prosecution data the EPO simply does not have: transaction history (`/transactions`), continuity chains (`/continuity`), and foreign priority claims (`/foreign-priority`). The six tools split along those endpoints rather than trying to synthesise a single "enrichment" tool - each fetch is expensive enough on its own that the thinker should decide which ones to spend budget on.

## Architecture

### Start reading

- `UsptoOdpThinker` - the agentic entry point, exposed to higher-level agents as a tool.
- `UsptoOdpClient` - the HTTP layer, including the 404 semantics every tool depends on, and the lookups the tools share: `findApplicationByPatentNumber` (the file wrapper behind a patent number), `applicationNumber`, `applicationNumberFor` (the application number given, or the one resolved from the patent number), `grantXmlUri` and `downloadXml` (the split grant XML behind associated-documents), and `sectionText` over the `ABSTRACT`, `CLAIMS` and `DESCRIPTION` elements of that XML.
- `UsptoOdpSearchTool` - canonical example of the opensearch DSL and the `parseMetaDataToArtifact` helper reused by every other tool.

### Which tool do I pick?

| Need | Use |
|------|-----|
| Find US patents by title / assignee / inventor / CPC / filing-date range | `UsptoOdpSearchTool` |
| Biblio + abstract for a known US patent number | `UsptoOdpFetchTool` |
| Full claims + description body (expensive - XML download) | `UsptoOdpFullTextTool` |
| Prosecution event history / legal status | `UsptoOdpLegalEventsTool` |
| Parent/child application chain (US continuity) | `UsptoOdpContinuityTool` |
| Earliest priority date + foreign priority claims | `UsptoOdpForeignPriorityTool` |
| International / non-US coverage | Not here - use `ext.patent.epo` |

### Endpoint map

```
POST patent/applications/search                                (all tools that take a patent number start here)
GET  patent/applications/{appNum}/associated-documents         (Fetch, FullText - yields grant XML URI)
GET  patent/applications/{appNum}/transactions                 (LegalEvents)
GET  patent/applications/{appNum}/continuity                   (Continuity)
GET  patent/applications/{appNum}/foreign-priority             (ForeignPriority)
GET  <grantDocumentMetaData.fileLocationURI>                   (FullText, Fetch - raw WIPO ST.36 XML on bulkdata CDN)
```

Everything except search is keyed by application number. Every subpath tool that only receives a patent number resolves it to an application number via a preliminary `/search` call, costing an extra request and an extra rate-limiter slot.

## Invariants and how-to

### Rate limiter is strictly sequential

`UsptoOdpRateLimiter` uses `maxRequests=1` with a 250ms window. That is deliberate: ODP enforces `burst=1` per API key (one in-flight request at a time), and parallel calls get 429'd even when the per-second budget looks unused. Do NOT tune the limiter to allow concurrency; the `burst=1` ceiling is an ODP-side rule, not a framework-side rule. Every tool in this package must attach this same singleton:

```java
private static final UsptoOdpRateLimiter RATE_LIMITER =
        RateLimiterFactory.getInstance().getRateLimiter(UsptoOdpRateLimiter.class);
```

### 404 has two meanings - pick the right client method

ODP returns HTTP 404 both for "no search matches" and for "application number does not exist". `UsptoOdpClient` splits this into two pairs of methods:

| Call | 404 behaviour | Use for |
|------|---------------|---------|
| `searchPostJson` / `searchGet` | returns `null` | search endpoints (empty result is valid) |
| `postJson` / `get` | propagates `Http404Exception` | fetch-by-ID endpoints (404 = `ResourceNotFoundException`) |

Using the wrong method masks real missing resources or turns empty searches into thrown exceptions. Fetch-flavoured tools that call the search endpoint to resolve an app number still use `searchPostJson` and translate an empty result bag into `ResourceNotFoundException` explicitly - that conversion is `findApplicationByPatentNumber`'s job, not the transport methods'.

### Opensearch DSL shape

Search bodies are built field-by-field in `UsptoOdpSearchTool.buildSearchBody`. The non-obvious bits:

- Query terms are ANDed into a single `q` string like `applicationMetaData.inventionTitle:foo AND applicationMetaData.applicantBag.applicantNameText:bar`. Colon-separated field paths are ODP-specific - they are not Lucene and do not accept quoting.
- Date ranges go into `rangeFilters`, not `q`. They filter on `applicationMetaData.filingDate` regardless of what the caller may want.
- `filters` defaults to `publicationCategoryBag = ["Granted/Issued"]`. Set `includeApplications=true` on the input to drop this and include pending applications.
- `fields` whitelisting is mandatory if you want a slim response; ODP returns the full wrapper otherwise.

### Full text is a three-hop process

`UsptoOdpFullTextTool` is the only tool that downloads patent body text, and it is not a single API call:

1. POST `/patent/applications/search` with `patentNumber` to get the application number and biblio.
2. GET `/patent/applications/{appNum}/associated-documents` to get the `grantDocumentMetaData.fileLocationURI` (falls back to `pgpubDocumentMetaData`, the pre-grant publication, when no grant has issued; with neither, the tool fails with `ExternalServiceException`).
3. GET that URI directly from the USPTO bulkdata CDN. This is raw WIPO ST.36 Redbook XML, parsed by regex on `<abstract>`, `<claims>`, `<description>`. No real XML parser - the tags are well-behaved enough that regex + tag stripping gives clean text.

The CDN download counts against the rate limiter slot held by the tool, so the entire three-hop sequence is one logical ODP "request" from the framework's view.

### Adding a new ODP endpoint

1. Input POJO with `@LLMDescription` on each field. If the endpoint is keyed by application, accept BOTH `patentNumber` and `applicationNumber` and resolve them with `client.applicationNumberFor(patentNumber, applicationNumber)`, which returns the application number given, else resolves it from the patent number, and refuses input carrying neither.
2. Tool extends `AbstractTool`, owns one `UsptoOdpClient`, attaches `UsptoOdpRateLimiter` (never create a new limiter).
3. Pick `get`/`postJson` (propagates 404) vs `searchGet`/`searchPostJson` (swallows 404) based on fetch-by-ID vs search semantics.
4. Reuse `UsptoOdpSearchTool.parseMetaDataToArtifact` if the endpoint returns `applicationMetaData` shaped payloads. Do not re-derive the kindCode extraction.
5. Register the tool in `UsptoOdpThinker.declareDefaultTools()` and describe it in the prompt's `TOOLS` list.

### Configuration

- Secret `UsptoOdpClient.SECRET_ID` (`myodp-api-key`) in the deployment's `Secrets` store. Register at https://data.uspto.gov.
- A missing secret is logged when `UsptoOdpClient` is first loaded; the requests then go out without the `X-API-KEY` header, and ODP's refusal reaches the tool as an `UnauthorizedException` through `AbstractApiClient`'s status mapping.
