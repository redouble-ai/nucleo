# Inside the demo

This page is for people working on the demo itself: how the Spring host is configured, every
endpoint with its request, its response and the streams the page draws from, the rules of the
catalog edits, the pricing arithmetic, the command-line discovery and the skills setup. What
the demo shows, step by step, is in [The demo and its endpoints](PACKAGE.md).

The demo is one engine, `nucleo-demo-engine` - the agent, the workflows, the index, the
corpus, the page, and the read and write logic behind every endpoint - served by two hosts
that add only their framework's wiring and no logic of their own: this one, a Spring Boot
process (`nucleo-demo`), and a Quarkus one (`nucleo-demo-quarkus`, which runs on the JVM or
compiled to a native image). This page describes the Spring host and the endpoints both serve,
computed once in the engine so the two can never drift; the
[Quarkus page](../../../../../../../nucleo-demo-quarkus/src/main/java/ai/redouble/demo/quarkus/PACKAGE.md)
describes only what its framework wires differently.

The Spring host is a Spring Boot process running the runtime in-process. It is the shape a
host application copies: the dispatcher's lifecycle bound to the application context,
credentials from the environment, one HTTP door that submits a job and returns its result.

## Running it

```
mvn -pl nucleo-demo spring-boot:run
```

or the packaged jar:

```
java -jar nucleo-demo/target/nucleo-demo.jar
```

With no credential in the environment the process starts, the page (`GET /status`) reports
every provider on the classpath as unconfigured, and `POST /ask` is refused with the runtime's
message naming what to provide. Set one credential and the same question is answered:

```
export ANTHROPIC_API_KEY=...
curl -s localhost:8080/ask -H 'content-type: application/json' \
  -d '{"question":"What is two plus two?","context":"arithmetic","grade":"SMALL"}'
```

The credential ids and their environment variable names are listed in
`nucleo-core/.../secrets/PACKAGE.md`: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, and for Bedrock
AWS's own `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_REGION` (or a profile, or the
role the process runs under: whatever the AWS default chains resolve).

## What is configured where

- **Runtime configuration**: none of the demo's own. The starter, `nucleo-spring-boot-starter`
  (`ai.redouble.nucleo.spring`), registers the one `NucleoConfigurator` on the classpath, which
  assigns `SpringSecrets` as the credential store, so the runtime's credentials are Boot
  properties, `nucleo.credentials.<name>` (with `.user` and `.host` parts), bound in
  `application.yaml` the way `spring.datasource.password` is: the demo binds them from
  environment variables, a deployment from a profile, a vault or a Kubernetes secret. AWS is the same three values Spring AI binds under `spring.ai.bedrock.aws`:
  the region, and the key pair as user and secret; with either half of the pair empty the Bedrock
  provider hands the AWS SDK its own chain, so a profile or the role the process runs under works
  with nothing exported, and the region is never defaulted. Everything else, the catalog backend
  and the picker, runs on the settings classes' shipped defaults.
- **Catalog**: the host module's `src/main/resources/models.json` (`DemoHome.catalogFile()`),
  where the build carries it onto the classpath the runtime reads. `main` calls
  `DemoHome.configure(NucleoDemoApplication.class, "application.yaml")` before anything reads a
  catalog, so the web run and the `discover` command share it. It finds the module from where
  the class loader serves the module's own `application.yaml`, then the class file, then the
  executable, by Maven's standard layout: the nearest `target` directory, above the IDE's
  `target/classes` or above the jar, whose own parent holds a `pom.xml`. It then names the file to the runtime through
  `nucleo.models`, set in code, so an edit made since the jar was built still reaches the
  process. An operator's own `-Dnucleo.models` is left alone and is the file the page edits;
  outside a module nothing is named and the build's copy serves, read-only. The file appears
  from a person's action alone, never from a start: with none yet the runtime serves the
  providers' shipped fragments and the page says so, its table still editable, since the
  first edit materializes those fragments (`META-INF/nucleo/seed_models.json` in each provider
  artifact, merged into one catalog and stamped with the providers it was written for) for
  the edit to land in, with no provider pinged. Discover writes the account's models, limits
  and orders into the same file, and because no file precedes a first Discover, it pings
  every entry rather than keeping any seeded fact. `DemoCatalog` reports at startup the catalog
  the process runs on; with no catalog anywhere it names the places it looked and the terminal
  steps that write one, and the page says so at the top. The discovery is the person's to run,
  through the page, their coding agent or `AGENTS.md`; the demo never runs it unasked. The
  tests run on `src/test/resources/test-models.json`, named by surefire with `-Dnucleo.models` so a
  discovered file never reaches them: priced Bedrock entries for MICRO, SMALL, MEDIUM and XL,
  each grade from MICRO to XL ordered, and one embeddings entry, pinned. With no credential on
  the checkout every order is skipped, so a model tier's row is the resolution's refusal
  naming the credential to provide, which is what they assert; the cap test holds a made-up
  Bedrock credential of its own for the one request, so the resolution succeeds and the cap
  refuses at admission before anything is sent.
- **Model choice**: `DefaultModelPicker` serves a request of a grade from the first entry of
  the grade's order (`pins`) the deployment can call, the envelope permits and that accepts
  what the request declares it sends, else from the grade's cheapest such entry, else from
  the grade above, with a warning naming the choice. The reading step's vision calls declare
  what they send (`ModelBinding.setSends`): an image as images, a PDF sent whole as a document.
- **Lifecycle**: the starter's `NucleoRuntime`, a `SmartLifecycle` bean in a phase below the
  web server's: the dispatcher starts before the first request can arrive and drains after the
  last one, within `server.shutdown: graceful` and `spring.lifecycle.timeout-per-shutdown-phase`.
- **Observers**: the starter subscribes `EventLogger`, so job states appear in the process
  log, and the `CostLedger` it registers as the spend gate. A host that records usage or
  exports metrics declares its own `NucleoRuntime` bean and subscribes there; the demo does not.
- **Logging**: Boot's own logback; the runtime logs through SLF4J, `ai.redouble` at INFO, and
  `ai.redouble.demo.DemoBenchmark.progress` at DEBUG so the benchmark's runs-finished
  count shows while every other job's progress, which `EventLogger` writes at DEBUG, does not.

## Endpoints

- `GET /` - the page, `nucleo-demo-engine`'s `META-INF/resources/index.html`, over the stylesheet
  `demo.css` and the script `demo.js` beside it: the credentials and the catalog at the top
  (stating the shipped defaults as the starting state until Discover writes the catalog), then
  one form per step below, each rendering its endpoint's result as tables. The benchmark's cost
  and judge columns are heat-mapped from best to worst, as the runtime's log table is. Each
  step carries one line of text and what to expect under a fold, and its body class hides the
  script's own explanatory notes (class `brief`). `detailed.html` is the same demo on
  the same script, every form and id the same, with every step explained and the notes shown.
  The two pages link to each other from the title bar.
- `GET /docs` - the documentation site, generated into the engine's jar at build time
  (`META-INF/resources/docs`); the bare path forwards to its `index.html`.
- `GET /status` - the runtime's status: dispatcher state, every provider on the classpath with
  whether its credential is present, how to provide it, and the credentials it declares it
  reads with their parts and environment variables (the page's connection form asks for
  exactly those, so it can neither invent a variable nor miss one), the catalog (`DemoCatalog.Status`:
  the file expected, the file read, the file the page's edits land in when the catalog in use
  is a file of the deployment's own, entries, `orders` - per grade, the entries the catalog
  places, in its order - `pins` - the embeddings and decision entries - and the instructions
  when something is missing), per grade the entry the runtime serves a text request, a
  request with images and one with documents with now (`serving`, asked of the runtime's own
  resolution, and `servingRefusals`, the refusal's words where it serves none), every entry
  loaded with its prices and what it accepts beyond text (`vision`, `documents`), whether the runtime has a seat for it (an
  entry of another modality - image, speech, video - is listed with its output modalities,
  badged "not callable" with the reason on hover, and counted callable nowhere) and whether the process's compliance
  envelope permits it (an entry it refuses - a model served only under provider data sharing
  - is badged "not permitted" with a "why?" bubble saying the default configuration refuses
  models that must share data with their provider, since Nucleo is designed for regulated work,
  counted callable nowhere, and its grade is not offered, since
  the picker is never allowed to land on it), and its status (a closed entry - disabled by
  the deployment, deprecated by the vendor, unlisted by the account - is badged with the
  status and counted callable nowhere). The page lays the entries out as the catalog is
  shaped: one group per rung from MICRO up, then the embeddings entries, then the decision
  entries, then the entries it never calls, each under a separator naming the group. A
  rung's separator names the entry serving its text, image and document requests, from
  `serving`, with the refusal on hover where none serves. A rung's rows are numbered in the
  order the runtime walks them: the placed entries in the catalog's order, a strong number
  each, then the callable rest by list price, input plus output, a muted number each, since
  that is how the runtime fills in; a placed entry that cannot be called keeps its place,
  dimmed and badged with the reason; an unplaced one that cannot be called sits unnumbered
  at the bottom. Every other group's rows sort by connection, then input and output price,
  then id. Each LLM row is badged with what it accepts beyond text, images and documents. On
  a file of the deployment's own a rung's numbered rows drag, or move by their arrows, and
  any move writes the numbered sequence as the grade's order through `/catalog/order`; a
  "clear order" button on the separator hands the grade back to its cheapest entries. The
  embeddings and decision pins are radios, with a clear button on their separator; the grade
  is a select whose change moves the row to its new group; and "enabled" is a checkbox for
  the two statuses a person owns, offered neither on the embeddings or decision entry pinned
  nor on one the vendor or the account closed. The catalog callout lists each grade's first
  entry and how many follow it, and the two pins, each marked with the reason where it cannot
  be called; the ask and agent steps' serves line is the grade's text server from `serving`,
  saying whether it is the order's first, a later entry of the order, the price fill-in or
  a grade above. The benchmark step, once the race is over, proposes an order per grade from
  the judge's scores: among the models whose mean score is within one point of the grade's
  best, the cheapest first, then the rest by score, then the grade's previously placed models
  the race did not score. "Apply this order" writes each proposed grade's order through
  `/catalog/order`. Then the
  shipped corpus's absolute path on
  this machine (`DemoCorpus`: a `corpus` directory under the working directory, else the
  checkout's `src/main/resources/corpus`, else the classpath copy extracted once per process
  into a temporary directory, file by file as `corpus.manifest` lists them, so a native
  image, which cannot list a classpath directory, carries the same corpus - the page's
  "read a directory" form opens prefilled wherever and on whatever OS the process runs), and `corpusAsOf`, the day the corpus's price story is
  answered for (`DemoCorpus.AS_OF`, 1 June 2026, when the last price the documents decide
  takes effect), which the page prefills both pricing steps' as-of inputs with.
- `POST /connect` - credentials pasted into the page, held for this process only: the body is
  `{records: [{id, user, secret, host}]}` with the runtime's record ids
  (`anthropic-api-key`, `aws-access-key-id` with `user`/`secret`, `aws-region`). They land in
  `SessionCredentials`, a runtime property source ahead of every other, so `SpringSecrets`
  sees them through the same machinery a vault uses - never a file, never echoed back, never
  logged beyond the id. Refused with 409 for a credential the deployment already configures
  (clients are constructed once around a credential and shared; session credentials fill
  gaps, they never override the deployment's), all records validated before any is held. A
  credential the session itself provided may be corrected - the affected providers' cached
  clients are evicted (`ClientProviders.evict`) and their discovery fingerprints dropped, so
  a mistyped endpoint needs a resubmit, never a restart. What is held is tested right then:
  the first provider on the credential that can list models makes that one authenticated,
  token-free call, and the card shows the count or the provider's own failure. Answers
  `{verifiedBy, listed, failure, note, status}`; the status's provider rows carry a `session`
  flag the page keeps the correction form open for.
- `POST /discover` - the catalog discovery on a click, and incremental: only providers whose
  credentials are new or changed since their last discovery this session are listed and
  pinged (`CatalogDiscovery.run(includeOlder, scope)`; what ran is remembered as a SHA-256
  fingerprint of the credential parts and connection facts, never a value), everything
  already discovered rides through untouched. The result (models, limits, pins - never a
  credential) is written to the demo's catalog file (`DemoHome.catalogFile()`) and
  `Models.reload()` adopts it live; a process outside its module has no such file and is
  refused with 409 before any provider is pinged, so the steps below use it without a restart. A provider whose listing
  FAILED is not marked discovered, so the next click retries it alone. Refused with 409 when
  no provider holds a credential, and when every connected provider was already discovered
  with these exact credentials. Answers
  `{report, file, entries, scope, providers, classifiedBy, classified, classifiedDropped, nonZdr,
  unserved, classifierFailure, status}`: `providers` is each queried provider's outcome (listed
  count, how many of the listed stayed unclassified, or the failure), `classified` names the
  entries the discovery's own classifier wrote - the strongest callable model grades the listings
  nothing could inherit a shape from, under the deterministic gates the discovery doc states,
  each entry pinged live and carrying a verify note - and `classifiedDropped` the ones whose own
  ping refused them. `nonZdr` (not zero data retention) names the new entries left out because the model refuses the
  zero-retention mode the account runs at (served only under provider data share; the Mantle
  listing is where they are reachable), and `unserved` the listed models no provider key on their
  platform has a client for (an embeddings family with its own request shape, a vendor the
  surface's SDK does not speak). The page shows one line per connection (the most any of its
  provider keys listed, or the failure when every key of it failed) and the classified entries.
- `POST /catalog/entry` - one entry of the deployment's own catalog file edited from the
  models table: the body is `{id, grade, status, inputPricePerMillion, outputPricePerMillion,
  confirmed}`, a null leaving a field as it is (`DemoCatalog.editEntry`). A grade is a rung of
  the ladder, on an entry that carries one; a status is OPEN or DISABLED, a person's two
  words, never the vendor's DEPRECATED or the account's UNLISTED; the pinned embeddings or
  decision entry is not disabled under its pin, and a placed entry is, keeping its place
  while the runtime passes over it. An entry moved to another grade leaves the order of the
  grade it left. A price is a non-negative number in the entry's currency, an output price
  only on an LLM entry, since an embeddings or a decision call has no output the provider
  bills. An entry the discovery inferred carries `unverified` (the status reports it per
  entry with the note); a grade or price edit, or `confirmed: true`, removes the mark. The
  file is validated by
  loading before it is written, `Models.reload()` adopts it live, and the answer is the
  status the page re-renders from. A refused edit is a 400 carrying the rule it broke; with
  no file of the deployment's own to land in (the shipped defaults, or a copy packaged on the
  classpath), a 409 naming Discover as the step.
- `POST /catalog/order` - one grade's order written from the page: the body is
  `{grade, ids}`, the ids in the deployment's order, the first the grade's default, an empty
  list clearing it (`DemoCatalog.order`). The loader's own rules apply on top: every id an
  entry of the catalog, a graded LLM entry of the grade or above, each once. Same
  validation, write, reload and answer as the entry edit.
- `POST /catalog/pin` - the embeddings or decision pin set or cleared from the page: the
  body is `{slot, id}` where the slot is `embeddings` or `decision`, the id an open entry's,
  null clearing the slot (`DemoCatalog.pin`); a grade is refused, since its entries are
  ordered instead. The loader's own rules apply on top: the embeddings pin names an
  embeddings entry, the decision pin a decision entry. Same validation, write, reload and
  answer as the entry edit.
- `POST /ask` - the body is the runtime's `QuickLLMQuestionInput` (`question`, `context`,
  `grade`); the response is a `QuickAnswer` (`answer`, `reasoning`, `model` the catalog
  entry that answered, `servedModel`, and `cost` with its `currency` as the catalog prices the
  calls, both null when the entry carries no price), built from the calls the job's own
  completion event carries (`QuickAnswer.capture`, subscribed before the job is submitted; the
  dispatcher releases a job's recorded calls from its context once it settles, so the context
  is no source).
  The workflow's owner is the request's principal; this process has no login,
  so it is `anonymous`, and a host with authentication puts its user there.
- `GET /skills` - every skill the runtime's loader found on the classpath: name, description,
  the origin the bundle declared, its bundle id, and the tools it suggests.
- `GET /agent` - the demo agent's palette (every tool name its model would see this turn)
  and its skill catalog (every skill its model may admit).
- `POST /agent` - the body is an `AgentRequest` (`query`, optional `grade`: a rung the agent
  runs at instead of the MEDIUM it declares, set on the agent the way the benchmark re-aims it
  per candidate). The response is the run as it happens, newline-delimited JSON through
  `AgentTrace`, an observer on the runtime's message bus scoped to the run's workflow and
  filtered to the agent's job and every job under it (each job's parent chain reaching the
  agent): one `job` line per event, naming its job (`id`, `parent`, `type` - the `JobType` -
  `name`, `tool` as the model calls it from the class's `@ToolName`, `action`, `iteration` as
  the orchestrator stamped it, `own` on the agent's job), the time, and a `kind`: queued (a
  tool job's carrying `input`, the arguments of the first call of its tool's name the turn
  decided on and no job claimed), started with the attempt, progress with the message and
  percent, note with the notification's `title`, `severity` and `message` (the model's
  reasoning between turns comes as one titled "Reasoning"), answer with the streamed answer's
  `content` (the typed answer as JSON), retry with which kind in `retry`, admission with the
  limiter's name, `state` and `waitMs`, completed (a model turn's carrying `turn`:
  `finalAnswer`, `reasoning`, `toolCalls` as `[{name, input}]`, and `call`: the entry, the
  model that served it, tokens, latency, price, stop reason; a tool's carrying `result` as
  the tool returned it), failed, timed_out and cancelled with the message. Every terminal
  line carries `measured`, the run's totals so far (`RunMeasure`: calls, tokens, latency,
  cost, the model that served, the wall time), summed the way the benchmark's rows are. The
  last line is `workflow_complete` with the `DemoAnswer` (`answer`, `skillsUsed`) and the
  totals, or `workflow_failed` with the message, written by the trace itself when the agent's
  own terminal event comes through the bus: the dispatcher publishes it before it settles
  the handle and the bus delivers in order, so every line of the run precedes the last one
  (the handle's holder only waits for that end and unsubscribes, ending the stream itself
  only if the run settled without its terminal event ever arriving). The page draws the lines as a timeline: a
  turn per model call, the calls it decided on as cards under it side by side, a sub-agent as
  a card with a rail of its own, timers ticking while a job runs, patched in place on every
  tick so nothing flickers.
- `GET /decide` - the decision agent's objective and palette (`DecideCapabilities`): each tool's
  name, what it does, the artifact type it takes and the one it produces.
- `POST /decide` - the decision agent run, `ai.redouble.demo.decide`: the body is a
  `DecideRequest` (`directory`, an absolute path; the shipped corpus's is on the status). The
  response is the run as it happens, newline-delimited JSON through the engine's
  `DecisionTrace`, scoped and filtered the way the agent's stream is: one `job` line per
  event, its job carrying `decision` (true on a decision call) beside the fields the agent's
  lines carry, and a `kind` as there; a decision call's completed line carries `decision`,
  the whole exchange (`state`: the objective, one line per artifact, the moves made;
  `questions` by id with `type`, `instructions` and a choice's `options`; `answers` by id with
  a choice's `choice`, `probabilities` and `confidence`, a noul's `probability`, or a score's
  `score`, `probabilities` and `confidence`; `call`: the
  entry, the model that served, tokens, latency, price), a tool's completed line `result`
  (the artifact's `ref` and `digest`, a list's `iterands` the same way). The last line is
  `workflow_complete` with `answer` (the statements selected: `ref`, `digest`, `artifact`),
  `turns` (the thinker's own record of every turn's distributions) and `measured` (with
  `model`, the catalog entry that decided, beside `servedModel`, the endpoint's own name), or
  `workflow_failed`; the stream ends from the run's own terminal event, the handle's holder
  ending it itself only when that event never arrived, as for the agent. The page draws each
  turn as the decision that opened it (bars per option, the chosen one marked; the state the
  model saw under a fold) and the tool that ran on it.
- `POST /decide-prices` - the pricing run with its grouping put to a decision model: the same
  body and response as `POST /pricing`, the same doer, reading tier, reconciliation and
  report; only the grouping tier differs (see the pricing demo below). 409 when the index is
  empty.
- `POST /extract` - the demo's workload, `ai.redouble.demo.extract`: the body is an
  `ExtractRequest` (`directory`, optional `maxFiles`, `budgets` as `[{amount, currency}]`, one
  cap per currency); the response is an `ExtractReport`: one row per file with its tier
  (`DETERMINISTIC`, `VISION`, `CLASSIFIER`, `SKIPPED`, `NEEDS_PERSON`, `REFUSED`, `FAILED`),
  chars, the model and what its calls cost, and a note; the spend per model; the caps; the
  totals. A row's cost folds in the embedding of its text, priced like every call, while the
  row's model stays the model that read the file - null when code read it - and never the
  embeddings model; a row whose calls were priced in more than one currency carries a null
  cost and says so in its note. Every file goes through the cheapest tier that reads it, in parallel under
  admission, and every text is embedded into the in-memory `FileIndex`. The caps are
  registered on the cost ledger, so the dispatcher refuses at admission every model call the
  cap cannot cover.
- `POST /search` - the body is `{query, k}`; the response is the top `k` index entries by
  cosine, with an excerpt. Refused with 409 before any extraction.
- `POST /pricing` - the second demo, `ai.redouble.demo.pricing`, on the first one's result:
  the body is a `PricingRequest` (`output` path, optional; `budgets` as for the extractor); the
  response is a `PricingReport`, written to `output` with Boot's own mapper so the file is
  the response. Refused with 409 before any extraction.
- `POST /benchmark` - the demo agent wrapped in `DemoBenchmark`, the runtime's `Benchmark`
  whose judges wait behind the same person the raced agents do: the body is
  `{query, runs}`; the agent answers the query once on the strongest model the deployment
  serves (the reference, resolved through `Grade.CEILING` exactly as the judge is) and then
  `runs` times on every open model of every grade whose provider holds a credential - the
  whole ladder, MICRO to the strongest, since the interesting result is the spread across
  rungs - and the strongest model judges every answer blind; the page sorts the table by the
  judge's score, best answers on top. The response is the race as it runs, newline-delimited
  JSON through `BenchmarkRace`, an observer on the runtime's message bus scoped to the race's
  workflow that borrows the chat wire's frame types: first a `status` line with the plan (one
  row per run, in race order), then a `progress` line per event of every run, judge and model
  call (`row`, `role`, `kind` - queued, started, progress, retry, completed, failed, timed_out,
  cancelled - the message, the attempt, the time; a retry says which kind in `retry`:
  `rate_limit`, `upstream`, `correction` or `truncation`, so the board counts the provider's
  failures apart from the model's own answers being re-asked; a call's events reach its run's
  row by lineage; a terminal event's line carries `measured`, the row's spend so far summed
  from the responses it carries and priced from the catalog - calls, tokens, latency, cost,
  the served model, the run's wall time; a judge's own calls are never summed into a row -
  and a judge's completion carries its `score` (the `accuracy` and `reasoning` halves beside
  it, with any `toolFault`) and `reason`), `status` lines with the benchmark's own headline, and last `workflow_complete`
  with `{answer, report}` - the reference's `DemoAnswer` and the `BenchmarkReport` the runtime
  also logged as a table, one row per run (model, calls, iterations, tokens, latency, cost, the
  judge's score and reason) and the means per model - or `workflow_failed` with the message.
  The page keeps one table: a row per run with its live status and what it is doing, gaining
  its spend and the judge's verdict as they land and re-sorting by the judge's score, price
  breaking ties; when the last line lands the report's ledger figures replace the running ones
  and the answer heads the table. Refused with 400 without a query or a run count, and with
  500 before the stream opens when no grade has an open model whose provider holds a
  credential, since that race could only fail; a failure after the race starts arrives on the
  stream as `workflow_failed`.

Every job the demo submits - the agent and the calls it makes, the extract and pricing tools,
the ask tool, the benchmark's judges - allows one transparent re-run on an upstream retry
signal (`DemoPolicy.UPSTREAM_RETRIES`, against the runtime's default of three): a person waits
behind each in a browser, a blip clears on the second attempt, and an endpoint still failing
then shows as a failed row or section within a minute instead of holding a race or a page.

## The pricing demo

Every document in the index goes to `ExtractPricesTool` (SMALL, one call per document, all
in parallel): the model lists the prices the document states as `PriceMention`s, each with
the product, the audience (who pays: retail, dealer, unspecified), the kind (`LIST`,
`TRANSACTION`, `PROPOSED`, `FORMER`, `COST`, `CHANGE`, `DISCONTINUATION`), the amount and
currency (a `CHANGE` carries a `percentChange` and no amount), the dates the document
attaches (`effectiveFrom`, `statedOn`; a date in the file name is the document's when the
text has none) and the words it came from.
The distinct product names then go once to `CanonicalizeProductsTool` (MEDIUM), which says
which names mean one product. `PriceHistory` does the rest with no model in it: a point's
date is its effective date, else the statement's, else the document's; per product,
audience and currency the `LIST` and `TRANSACTION` points compete by date as of the day
the run answers for (`asOf` on the request, else today), the latest on or before that day
is `CURRENT`, one dated after it is `SCHEDULED`, every other statement of the same amount
is `CONFIRMED`, earlier different amounts are `SUPERSEDED`, two amounts on the same latest
date are a `CONFLICT` for a person, a point with no date is `UNDATED` and never ranked; a `CHANGE` becomes a price first, its
percentage applied to the latest price in force before its date (earlier changes first, so
a change can build on a change), and competes from its date, or stays `UNAPPLIED` with the
reason when nothing single precedes it; `FORMER`, `PROPOSED` and `COST` never compete; a
`DISCONTINUATION` dates the product's end. `PriceHistoryTest` pins that arithmetic. `ExtractPricingDoer` runs the fan-out under the same caps as the extractor and
reports one row per document with what it cost.

On the shipped corpus the history is placed on purpose (`corpus-build/build-corpus.py`
says where): a 2025 list that calls itself replaced, the 2026 list effective 1 January in a
spreadsheet, a deck and an invoice, a Kestrel 1 change decided in the February meeting for
1 April and repeated by the changelog, an SQL script and a dated portal screenshot, a
20 percent rise from 1 June decided in minutes whose only date is their file name, a 2027
figure floated and not decided, a planning price in the product spec, a sign with the price
a tune-up used to be, supplier costs in a parts sheet, a phone note and a photographed
note, a discontinued model, and a whiteboard whose figures carry no currency. Read from
Bedrock the run finds about 75 mentions in 26 documents, and the Kestrel 1 gravel comes
out at 20 percent on its April price, with the April price and five earlier statements
beneath it.

`POST /decide-prices` is the same run with its grouping put to a decision model: the same
`ExtractPricingDoer`, documents, reading tier, reconciliation and report, with the two
model-facing tiers given to the doer as constructors and `DecideProductGroupsTool` in place
of `CanonicalizeProductsTool`. Of the run's two model judgments only the grouping is a
verdict over options code already holds, which is a decision model's shape: the first
decision is one verdict per pair of names, "do these two mean the same product", and every
pair the model puts at or above one half (`DecideProductGroupsTool.THRESHOLD`) joins their
groups; the second, for each group of several names, is a choice of which name is the
product's proper name, without a brand in front, a descriptor behind or a size. Every name
in a group is one the documents used. The pairs are the cost: a dozen names are sixty-six
questions in one decision. `DecideProductGroupsTest` pins it with a scripted model. On the
shipped corpus (2026-09-25, Kev-9B on a Mac, the same index as the run beside it), twenty
names were 190 pairs and the run took 39 seconds against step 6's 22, spending the reading
tier's 1.6 cents against 2.8 with the MEDIUM call; the decision model joined what the MEDIUM
model left apart ("Comet 2 city" with "Comet 2", "Halcyon Meridian 3" with "Meridian 3
trail", "Forks" with "Fork M3", "bearing swap" with "headset bearing replacement") and left
apart one pair the MEDIUM model joined ("Kestrel 1" and "Kestrel 1 gravel"); the current
prices came out the same.

## Discover mode

`java -jar target/nucleo-demo.jar discover`, run in `nucleo-demo`, runs `CatalogDiscovery` instead of
the web server, on the same credentials bound the same way, and exits with the discovery's
exit code: the demo's `main` names its catalog file (`DemoHome.configure`) and then makes one
call to the starter's `NucleoSpringApplication.run`, which provides the command. The starter's
`NucleoRuntime` stays down in that mode: the discovery seals its own permitting envelope to ask
what the account can call, and a started dispatcher would have sealed the refusing default
first. The discovery writes the file `-Dnucleo.models` names, which `main` has set to this
module's `src/main/resources/models.json`, whatever directory the command runs in: the same
file the web demo reads and the page edits, gitignored because no account's catalog is a
default. `AGENTS.md` at the repository root walks the setup.

## Skills

Two bundles reach the process, through one loader, and `GET /skills` shows both:

- **The demo's own**, under `nucleo-demo-engine/src/main/resources/META-INF/skills/demo/`:
  `concise-answers` and `show-your-work`, both of origin `skillsjars`, since the engine jar
  carries them. A bundle is a directory with a `SKILL.md` (YAML front matter with
  `name`, `description`, `allowed-tools`, `metadata`, then the instructions as the body) and
  any sibling resources.
- **A skilljar**, the project's public `nucleo-skills` artifact, a plain jar with the
  same layout under `META-INF/skills/`. It contributes `delegation` and `capability-tree`.
  Any jar laid out that way is found the same way; nothing in the demo names it.

`DemoAgent` declares two tools of its own (`get_current_time`, `calculate_dates`), a tool
catalog it may ask for through `request_tools` (`web_fetch`), and a skill catalog of
everything the registry holds. Each turn the runtime offers `request_skill` with that catalog
as an enumeration in the tool's input schema, so the model admits a skill by name from what it
was offered and nothing else; an admitted skill's instructions ride the system preamble of
every later call in that conversation. `GET /agent` is that offer, as the model sees it.

With a credential set:

```
curl -sN localhost:8080/agent -H 'content-type: application/json' \
  -d '{"query":"How many days until the end of the year? Briefly.","grade":"MEDIUM"}'
```

The lines arrive as the run goes: the `request_skill` tool's queued line carries
`{"skillNames":["concise-answers"]}`, its completed line the skill admitted, and the last line
the answer. The word "briefly" is the trigger the `concise-answers` skill describes, so a model
that reads the catalog admits it and the answer comes back one line long, with `skillsUsed`
naming it.
