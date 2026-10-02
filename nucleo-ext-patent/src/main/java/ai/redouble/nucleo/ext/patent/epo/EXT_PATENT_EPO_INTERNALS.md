# Inside the EPO tools

This page is for people working on the EPO extension itself: how `EPOClient` absorbs the OPS
protocol, how the per-service rate limit routes, and how to add an endpoint. What the tools do
for an agent, and the credential they need, are in [Patents: EPO](PACKAGE.md).

## Philosophy

EPO OPS is a legacy XML API with OAuth 2.0 auth, per-endpoint quota buckets surfaced out-of-band via response headers, and an aggressive bot detector that returns 403 (not 429) when it does not like you. Every one of those quirks lives inside this package so tools can stay dumb. `EPOClient` + `EPORateLimiter` absorb the protocol; tools just build a path, call `client.get(path)`, and parse the `JsonNode`.

We did NOT build a typed XML model. EPO's schema is deep, polymorphic, and single-vs-array inconsistent across endpoints; hand-rolling POJOs for it is a losing fight. Responses are parsed XML-to-JSON once and navigated with defensive `.path()` walks. Every extractor handles both `isArray()` and single-object variants.

## Architecture

`EPOThinker` (SingleObjectiveThinker, medium model, 10 iterations) is the agentic entry point. It owns 9 tools + `CurrentTimeTool`. All tools share the protocol through one client and one multi-bucket limiter:

```
EPOThinker
  |
  +-- 9 EPO*Tool classes --> EPOClient --> ops.epo.org/3.2
                               |              |
                               |              v
                               |         X-Throttling-Control header
                               |              |
                               +--------------+--> EPORateLimiter
                                                  +-- SearchBucket     (6/min)
                                                  +-- RetrievalBucket  (30/min)
                                                  +-- InpadocBucket    (20/min)
                                                  +-- ImagesBucket     (30/min)
                                                  +-- OtherBucket      (60/min)
```

Start reading: `EPOClient` (all the quirks live there), then `AbstractEPOTool` (what every tool shares: the limiter declared against its `EPOService`, the client bound at execution, the `publication(number, format)` path segment), then `EPOBiblioTool` (canonical tool shape), then `EPORateLimiter` in `../ratelimiters/` (multi-bucket routing). `EPOText` reads claims and description sections out of a full-text response, English first, raw XML as the fallback.

### Which tool for which need

The thinker will pick, but when wiring programmatically:

| Need | Tool | Notes |
|------|------|-------|
| Query -> list of patent numbers | `EPOSearchTool` | CQL or structured fields; numbers come back in dotted DOCDB (see below). |
| Patent number -> front-page metadata | `EPOBiblioTool` | Populates `PatentArtifact`; cheap. |
| Claims + description in one call | `EPOFullTextTool` | Three sequential API calls (biblio, claims, description); `ToolWeight` is 3. The biblio response is fetched but not carried into the artifact, which holds the number as asked, the claims and the description. Use only when body text is explicitly needed. |
| Claims only / Description only | `EPOClaimsTool` / `EPODescriptionTool` | Single retrieval call each. |
| Family across jurisdictions | `EPOFamilyTool` | INPADOC bucket. |
| Current legal status + events | `EPOLegalStatusTool` | INPADOC bucket. |
| PDF URL for a document parser | `EPOImagesTool` | Returns `EPO_BASE_URL + href`; pair with a PDF-parsing tool. |
| DOCDB <-> EPODOC number conversion | `EPONumberConversionTool` | OTHER bucket; use when another tool needs a format the LLM does not hold. |

## Invariants and how-to

### Every number an EPO tool reads out of EPO's data is dotted DOCDB

EPO has two formats: **DOCDB** (dotted, e.g. `EP.1000000.B1`) and **EPODOC** (concatenated, e.g. `EP1000000B1`). Every tool takes an `inputFormat` field defaulting to `docdb` and URL-encodes the number straight into the path.

Every number the tools read out of an EPO response is printed dotted DOCDB, built in one place, `EPOClient.docdbNumber`: the search results, the family members, the converted number in DOCDB, `PatentArtifact.patentNumber` and its application number from `EPOBiblioTool`. A number read from any EPO output therefore goes straight back into any EPO input with the default format. The one undotted number is the one asked for by name: `EPONumberConversionTool` with `outputFormat=epodoc`. The artifact's Espacenet URL is undotted too, because that is Espacenet's query syntax. `EPODescriptionTool`, `EPOImagesTool` and `EPOFullTextTool` echo the input number as given.

### The limiter is multi-bucket; pick the right bucket

Every tool names its `EPOService` in its `AbstractEPOTool` constructor call, and the base class declares `req.requireRateLimiter(RATE_LIMITER, service)`. The enum value routes to one of five `ElasticWindowRateLimiter` sub-buckets inside `EPORateLimiter`. Getting the bucket wrong does not break correctness but serializes calls that EPO would happily run in parallel. Mapping:

- `SEARCH` -> `/rest-services/*/search*`
- `RETRIEVAL` -> `/rest-services/published-data/publication/.../{biblio,claims,description,abstract}`
- `INPADOC` -> `/rest-services/family/*` and `/rest-services/legal/*`
- `IMAGES` -> `/rest-services/published-data/publication/.../images`
- `OTHER` -> `/rest-services/number-service/*`, classifications, everything else

`SearchBucket` is configured more conservatively than its siblings (throttle increment 2.0, 60s initial cooldown, 10min max cooldown) because the search endpoint is where EPO's robot detector fires hardest.

### OAuth is stateful inside the client

`EPOClient` lazily fetches a bearer token on first call via client-credentials against `/auth/accesstoken`, caches it for 19 minutes (1 minute margin from EPO's 20-minute expiry), and transparently refreshes on a 401 with one retry. Do NOT add retry-on-401 logic at the tool layer. Consumer key/secret are read from the deployment's `Secrets` store under `EPOClient.SECRET_ID` (`epo-api-key`, user = consumer key, secret = consumer secret) at static init; a missing secret logs a warning and fails later calls with `UnauthorizedException`.

### Robot detection masquerades as 403

EPO returns HTTP 403 with `CLIENT.RobotDetected` in the body when it thinks you are a bot. `EPOClient.classify()` maps this specific body-pattern to `Http429Exception`, which the dispatcher routes to the limiter as a rate-limit signal, not an auth failure. Do not inspect 403s at the tool layer; let the client classify them.

### X-Throttling-Control is a pre-emptive signal

Every EPO response carries an `X-Throttling-Control` header like `busy (search=black:0, retrieval=green:100, ...)`. `EPOClient.postProcessResponse` parses it on every success and calls `onAdvisoryPressure(service)` or `onAdvisorySuccess(service)` on the matching bucket. This lets the limiter slow down *before* we start collecting 429s. Never strip or bypass this path; the buckets rely on it for recovery as much as for backpressure.

### Search vs fetch error semantics

`EPOClient` exposes two flavors on purpose: `get(path)` is fetch-by-ID (404 -> `ResourceNotFoundException` via `Http404Exception`), `searchGet(path)` returns `null` on 404 because empty is a valid search outcome. Use `searchGet` only for actual search endpoints; retrieval/family/legal-status are fetch-by-ID.

### Full-text is not the same endpoint family

There is no single OPS call for biblio + claims + description. `EPOFullTextTool` fires three sequential calls and assembles a `PatentFullContentArtifact` from the claims and the description; the biblio call's response is not read into it, so a patent EPO does not know fails there with `ResourceNotFoundException` and its front-page fields stay empty. `ToolWeight(min=3, max=3)` reflects the cost. The inner try/catch on claims and description is deliberate: a patent may ship without one of those sections (older docs, translations), so we log and keep going rather than failing the whole artifact.

### XML response quirks

- The XML parser has external entities and DTD resolution disabled (XXE hardening in `EPOClient` static init).
- EPO embeds inline HTML (`<sup>`, `<sub>`, `<b>`, `<img>`) inside text content. Every text extractor passes results through `EPOClient.stripHtml` (jsoup).
- When JSON navigation cannot find the expected structure, claims and description extractors fall back to `EPOText.rawText`, which reads the element out of the XML string directly. Do not remove the fallback; EPO's schema varies by publication authority.

### Adding a new endpoint

1. Figure out which `EPOService` bucket EPO assigns it (the `X-Throttling-Control` header on a sample response tells you).
2. Extend `AbstractEPOTool`, passing that `EPOService` and the tool's timeout to its constructor; the base class owns the one `EPOClient` and declares `requireRateLimiter(RATE_LIMITER, service)`.
3. In `execute`: validate inputs (`publication(number, format)` builds the path segment and refuses a missing number), `connect(resources)` to bind the client to the job's HTTP client, `client.get(path)`, navigate the `JsonNode` defensively (check `isArray()` at every level). Throw `InvalidInputException` for bad input; let other HTTP errors propagate through the client's classification.
4. If the output includes text content, pipe it through `EPOClient.stripHtml`.

### Configuration

Secret `EPOClient.SECRET_ID` (`epo-api-key`) in the deployment's `Secrets` store. Register at https://developers.epo.org. The per-bucket QPM values inside `EPORateLimiter` are hardcoded against OPS's free-tier quotas; paid tiers get higher limits but the code does not read them from config today.

### MCP exposure

`EPOSearchTool` carries `@MCP`: exposable to external MCP clients through
`ai.redouble.nucleo.mcp.server`; the host's access policy decides per consumer.
