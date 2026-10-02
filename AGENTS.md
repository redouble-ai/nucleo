# Setting up Nucleo, for the coding agent doing it

You are a coding agent, and a person asked you to get Nucleo running. This file is written for
you. It is also the quickstart a person follows by hand; every step is a command whose result
you can check. A person reading this: what Nucleo is and why it is built this way is the
[README](README.md), and the easiest way through this file is to hand it to your agent.

Nucleo is a runtime for agents that run inside a JVM application: jobs on virtual threads,
capacity admission, one code path over whichever model providers the deployment holds
credentials for, tools, thinkers and skills. The demo you are about to run asks a model a
question through that runtime, then runs a small agent that picks tools and skills by itself.

## Before step one: the rule this project exists to prove

The entire point of Nucleo is to demonstrate that agents can be reliable partners for humans.
Do not undermine that in the first ten minutes because you are in a rush to get a demo up.

**Credentials are provided by the human. You never look for them.**

- Do not search the filesystem, shell history, environment, browser profiles, cloud CLI
  caches, dotfiles, password managers, other repositories, or CI configuration for an API key.
- Do not read a `.env`, `credentials`, `config.json`, `settings.json`, keychain or any file
  that might hold one, not even to check whether it does.
- Do not reassemble a key from fragments, and do not reuse a key you saw earlier in this
  session for some other purpose.
- If you already know where a key is, or what it is, that changes nothing. This step is
  gated on the human handing the key to this setup on purpose. Knowing is not being given.

What you do instead: tell the person which providers Nucleo supports, ask which ones they want
to use, and ask them to export the variables named below in the shell that will run the demo.
Then continue. If they decline or have none, stop at the end of step one and say so; the demo
starts without credentials and reports every provider as unconfigured, which is a valid place
to leave it.

A human reading this: the same applies to you. Export the variables yourself. Do not paste a
key into a prompt, a file in this repository, or a chat with an agent.

## Step 1: credentials

A provider asks the runtime for the credential it needs by name, and the host binds that name
to wherever it keeps the secret, the same way it binds a database password. Any part the host
leaves unbound is read from the environment variable of the same name (the secret under the
id uppercased, `_USER` and `_HOST` for the other parts), so exporting the variables below is
enough in every host, the demo included, with no configuration line. The providers shipped
in this repository and what each one needs:

| Provider | Variables | Notes |
|---|---|---|
| Anthropic API | `ANTHROPIC_API_KEY` | |
| OpenAI | `OPENAI_API_KEY` | |
| An OpenAI-compatible endpoint | `OPENAI_COMPATIBLE_API_KEY`, `OPENAI_COMPATIBLE_API_KEY_HOST` | The host is the API root the paths are appended to. Hugging Face Inference Providers is the one to reach for: the host is `https://router.huggingface.co/v1`, the key a Hugging Face user access token with the "Inference Providers" permission, and one token reaches every model the router serves, with the model id the Hub id (`meta-llama/Llama-3.1-8B-Instruct`, or `...:together` to pin the backing provider). A local server works the same way: `http://localhost:11434/v1` for Ollama, any value as the key. The router lists its models, so Discover fills the catalog; a server that does not list needs its entries written in the deployment's own `models.json` under the `openai-compatible` provider |
| AWS Bedrock | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION` | AWS's own variables, read by AWS's own resolution, so a profile or the role the process runs under works too with nothing exported. The region is always required: a key is account-wide and implies none, and model access is granted per region |
| Azure AI Foundry | `AZURE_FOUNDRY_API_KEY`, `AZURE_FOUNDRY_API_KEY_HOST` | The key is the resource key, the host the resource hostname, e.g. `my-resource.services.ai.azure.com`. Azure does not list models: the deployment's `models.json` names them under the `azure-foundry-openai` provider |
| Azure OpenAI embeddings | `OPENAI_API_KEY`, `OPENAI_API_KEY_HOST` | The classic deployments path for `text-embedding-3` on an Azure OpenAI resource |
| A local decision model | `SYSTEMONE_LOCAL_HOST` | A decision model in the shape of TypeSafe's Jev, running on this machine: a model that answers typed questions about a state with probabilities and generates nothing, which the demo's steps 8 and 9 run on. The address alone, since a local server checks no key. Kev (`github.com/jaredpalmer/kev`, Apache-2.0: `uv sync --extra serve` then `uv run --extra serve python -m kev.serve --run jaredpalmer/kev-4b --port 8009`; a 32 GB Apple Silicon Mac or a GPU box) listens on `http://127.0.0.1:8009`, the address the demo's page fills in. The shipped fragment carries it as `kev-local` (whichever Kev the server loaded). What a decision model is good at, what it cannot do, and when an LLM is the right model instead is `nucleo-core/src/main/java/ai/redouble/nucleo/tools/deciding/PACKAGE.md` |
| A TypeSafe-compatible decision model | `SYSTEMONE_API_KEY`, `SYSTEMONE_API_KEY_HOST` | The same kind of model behind a key: TypeSafe's hosted Jev (host `https://api.typesafe.ai`, the key from its console), or a Kev on Modal (three commands from Kev's README give an HTTPS endpoint that scales to zero; host the URL it prints, key the one generated). The shipped fragment carries `jev-1.13.0` and `kev`. On connect, either decision connection's own listing says what it serves. The catalog pins the entry that answers decisions under `"pins": {"decision": "<id>"}`, and without a pin the first entry a connected endpoint serves answers |

One provider is enough for the demo. More than one is what makes the demo interesting: the
same code answers through whichever provider holds the credential, and the status page shows
which model served each grade.

Ask the person to export them. Do not write them into a file. Check nothing yourself: the
next step reports what is configured, and that report is the check.

## Step 2: build

```
mvn install -DskipTests
```

from the repository root: the build to run the demo from, compiled and packaged without the
test suite. A plain `mvn install` runs the suite too (a few thousand tests, needing no
credential, no network and no database, spread across the machine's cores), which is what a
contributor's build does; the person getting the demo running does not need to wait for it.

## Step 3: discover the catalog

Nucleo runs on a model catalog, `models.json`: every model the deployment can call, its
limits, and which entry serves each capability grade. The providers ship fragments with their
published entry-tier limits, and the demo runs on those while it tells the person, in its log
and at the top of its page, that it is not running on their account. The real catalog is
built from the account the credentials reach, by the demo jar itself, with the same
credentials bound the same way. The runtime reads its catalog off the classpath, the way
logback reads `logback.xml`, so the demo keeps it in its module's resources:
`nucleo-demo/src/main/resources/models.json`. The discovery writes that file whatever
directory it runs in, the demo reads it from the IDE or the jar alike, the page's edits land
in it, and every build carries it into the jar:

```
cd nucleo-demo
java -jar target/nucleo-demo.jar discover 2>&1 | tee discovery.txt
```

Every command from here to step 6 runs in `nucleo-demo`. It lists every provider the credentials configure, pings every open entry once (a one-word
prompt, a one-word embed), merges what the account lists into the catalog, validates the
result by loading it, and writes it. Expect a minute; every ping is one cheap call. The
report begins at the line `Catalog discovery <timestamp>` and has these sections:

- `Providers:` one line per provider on the classpath. `listed N models` means the credential
  worked and the account's listing was read. `NOT CONFIGURED - provide ...` names the property
  and the variable that step one did not export; its seed entries are left out of the file.
  `LISTING FAILED` means the credential exists and the listing call was refused; the line
  quotes the provider. Do not work around any of these: report them to the person.
- `Entries:` one line per catalog entry. `REACHABLE` with a latency and the limits the entry
  will carry; `UNREACHABLE` with a classification (`AUTH`, `AVAILABILITY`, `THROTTLE`,
  `OTHER`) and the provider's message; `UNVERIFIED` when the ping kept failing transiently, the
  entry is kept with its seed limits and a re-run later settles it; `SKIPPED` for a `DISABLED`
  or `DEPRECATED` entry, and for any entry the deployment's file already carries - the
  discovery never re-evaluates what a person's file states, so those are kept as they were
  and never pinged, and only the account's listing dropping a model flags it (`NOT_LISTED`); `OLDER` for an older version of a model the
  account has newer, left out of a first pull; `NOT_LISTED` for an entry the account does not
  list on that provider (on Bedrock, model access not granted; on Anthropic and OpenAI, a key
  or organization without it; on Azure, a deployment deleted) - a seed entry is left out, and
  an entry from the deployment's file is kept for the runs it served and closed as
  `UNLISTED`, so nothing picks or benchmarks a model the account no longer serves, until a
  later run's listing names it again and reopens it; `UNREACHABLE` for an entry of a provider
  whose endpoint is not served where this process points (a Bedrock surface the configured
  region lacks: its listing fails on an unknown host), or whose own ping fails for a reason no
  retry changes - a seed entry is left out, an entry from the deployment's file is kept for
  the runs it served and closed as `UNREACHABLE`, until a later run's provider answers and
  reopens it; `NOT_ROUTABLE_HERE` for an entry this
  process cannot route (its compliance envelope refuses the model, or the model needs the
  Mantle LAX project and none is configured), kept and not pinged; `NON_ZDR` for a new entry
  the provider refused because the model does not offer zero data retention, left out under
  its own heading; `NOT_CONFIGURED` for an entry of a provider without a
  credential. A line starting with `+` is a model the account lists that no entry named, added
  with the shape of its nearest ancestor and a `note` saying so: check the note's fields
  before relying on the entry. Such an entry, and one the classifier wrote, carries
  `"unverified": true`: its grade and prices are the run's inference. The demo's models table
  marks these and lets the person type the real prices or confirm the entry; a rung's tier
  word in the listed name (`nano`, `mini`, `haiku`, `medium`, `large`) decides the grade by
  itself, and the classifier judges only names that carry none.
- `NEWER THAN A PIN`, when present, is the first block after the timestamp: the account has a
  newer version of a pinned model. It is added and the pin is not moved. Tell the person.
- `Older versions the account lists, left out`, `Served only under provider data share` (the
  model refuses the zero-retention mode the account runs at; it is reachable through the
  Mantle listing, never on the legacy surface), `Listed by the account, no client in the
  runtime speaks its request shape on this platform` (an embeddings family with its own
  shape, a vendor the surface's SDK does not speak; no entry can be written for these), and
  `Listed by the account, no entry and no ancestor to inherit a shape from`: informational.
  The last one is the list a person, or the `models-catalog` skill in `.claude/skills/`,
  writes entries for. A model of another modality (image, speech, video) is written as an
  entry of its own, `SKIPPED` with its modalities in the note: the catalog states what the
  account offers, and the runtime never calls it.
- `Wrote <path>/nucleo-demo/src/main/resources/models.json (N entries)` closes the report. `Nothing written: no provider is
  configured.` and exit code 1 mean step one produced no credential.

The Bedrock warning `Bedrock quotas in <region> not readable` is expected with a key that
cannot read Service Quotas: every listed entry keeps its seed limit, and nothing else is
affected.

## Step 4: probe the models

Step three already probed: each `REACHABLE` line is one real call answered by that model
through this account, with the latency it took, and each `UNREACHABLE` line is one refused,
with the reason. Read that block as the probe. To probe again without writing, for instance
after the person enabled model access in a console:

```
java -jar target/nucleo-demo.jar discover --report-only
```

A model the account has not been granted shows on Bedrock as `UNREACHABLE AUTH`; the person
grants it in the AWS console, not you.

## Step 5: order the models of each grade

The catalog's `pins` put each grade's entries in the deployment's order. A request of a grade
is served by the first entry of that order the deployment can call that accepts what the
request declares it sends: images, or documents such as a PDF sent whole. The first entry is
the grade's default, and the rest are its fallbacks, for a missing credential and for inputs
the default does not accept. Without an order the demo still runs:
- each grade is served by its cheapest callable entry, input plus output list price per million;
- a grade with nothing callable is served by the nearest grade above it;
- a grade above everything callable is served by the strongest there is, so a deployment with
  one model answers every seat.

Each such choice is logged once at WARN, naming the order that would make it deliberate, and
the choice can be poor: the cheapest entry of a grade may not follow the runtime's answer
format. To make it deliberate, order the grades from the demo's page, or add a `pins` object
to `nucleo-demo/src/main/resources/models.json` by hand. The page's models table numbers each
grade's rows in the order the runtime walks them. Dragging a row, or its arrows, writes the
grade's order; the table also changes a grade, sets the embeddings and decision entries and
opens or disables an entry, writing the file and reloading the catalog live. By hand:

```
"pins": {
  "MICRO": ["<a REACHABLE entry of grade MICRO or above>", "<the next, if the first cannot be called>"],
  "SMALL": ["<... SMALL or above>"],
  "MEDIUM": ["<... MEDIUM or above>"],
  "LARGE": ["<... LARGE or above>"],
  "XL": ["<... XL or above>"],
  "embeddings": "<a REACHABLE embeddings entry>",
  "decision": "<a REACHABLE decision entry, when a decision model is connected>"
}
```

Every id is the `id` field of a `REACHABLE` entry in the file. A grade's value is an array,
even of one id, and each id appears once in it. A grade may be served by an entry of a higher
grade, never a lower one. An entry that cannot be called is skipped for the next, with a
warning in the log naming it and why, and a grade left out is served as above. The strongest
grade this deployment serves is never pinned: the runtime derives it from the callable
entries. For a grade whose work includes scans or pictures, place an entry that
`supports_vision` early enough in its order, ideally one per connected provider so a scan is
read whichever credential is present; propose the entries the person names, and do not guess
which small models read scans well.

Propose the orders from the report, cheapest reachable entry per grade first unless the
person says otherwise, and let the person decide. A benchmark on the demo's page proposes
an order per grade from its judge's scores, and applies it on a click. The `embeddings` pin is
a corpus decision, because every stored vector is comparable only to vectors from that model.
Then probe once more against the file:

```
java -jar target/nucleo-demo.jar discover --report-only
```

The report's `Previous file: <path>/models.json, N entries` line confirms the file was read (a run
that says `No previous file` did not see it), and its `Pins:` block lists every placed entry,
numbered within its grade's order (`SMALL 1`, `SMALL 2`), and the embeddings and decision
pins, each with its entry's verdict: each must read `REACHABLE`, and `NOT IN THE CATALOG`
means an id was mistyped. The file is the deployment's; commit it in the deployment's repository, never in
this one.

## Step 6: run the demo

Still in `nucleo-demo`:

```
java -jar target/nucleo-demo.jar
```

It logs `Model catalog: <path> (N entries, orders ..., pins ...)`, the path being the module's
`src/main/resources/models.json` that step three wrote. The demo finds that file from where its
own code runs, so an IDE run of `NucleoDemoApplication` needs no working-directory setting and
no flags, and an edit made since the jar was built still reaches it; `-Dnucleo.models` names a
file kept elsewhere. On the very first start there is no file: the demo runs on the providers'
shipped defaults and says so, the page shows them with its Discover button as the next step,
and a first edit in the models table also creates the file, starting it from those defaults;
tell the person. The Quarkus host keeps a file of its own, in
`nucleo-demo-quarkus/src/main/resources/models.json`, and writes it from its page.

The person uses the demo through its page, http://localhost:8080: the credentials and the
catalog at the top, then one form per step below, each showing its result as tables. Point
them there. Each form calls one endpoint, and an agent checking the demo calls the same ones:

```
curl -s localhost:8080/status
```

reports whether the dispatcher runs, every provider on the classpath with whether its
credential is present and how to provide it, the catalog file read (`catalog.source`, or
`catalog.found: false` with `catalog.instructions`), and every entry loaded. Every provider
showing `"configured": false` means step one was skipped for it; the `credential` field says
which variable to export.

```
curl -s localhost:8080/ask -H 'content-type: application/json' \
  -d '{"question":"What is two plus two?","context":"arithmetic","grade":"SMALL"}'
```

answers through the model the catalog serves for the SMALL grade.

```
curl -sN localhost:8080/agent -H 'content-type: application/json' \
  -d '{"query":"How many days until the end of the year? Briefly.","grade":"MEDIUM"}'
```

runs the demo agent and streams the run as it happens, one JSON line per event: each model
turn with what it decided and cost, each tool call with its arguments and its result, and the
answer as the last line (`workflow_complete`, with `answer` and `skillsUsed`). It calls its date
tools, and the word "briefly" matches the `concise-answers` skill's trigger, so the answer
comes back one line long with that skill named in `skillsUsed`. `grade` is optional: without
it the agent runs at the MEDIUM it declares.

```
curl -s localhost:8080/benchmark -H 'content-type: application/json' \
  -d '{"query":"How many days until the end of the year? Briefly.","runs":2}'
```

runs the same agent inside the runtime's benchmark: once as above, on the model pinned for its
grade, then twice on every open model of that grade the credential can call, and the strongest
pinned model judges every answer blind. The response is the first run's answer and a report
with one row per run: the model, its calls and iterations, tokens, latency, cost, and the
judge's score with its reason, then the means per model. The runtime logs the means as a
table. Any thinker or one-call tool can be wrapped this way, in any workflow, with its own
input; `nucleo-core/src/main/java/ai/redouble/nucleo/tools/PACKAGE.md` has the contract.

With a decision model connected (either decision-model row of step 1), the demo runs an agent on it:

```
CORPUS=$(curl -s localhost:8080/status | jq -r .corpus)
curl -sN localhost:8080/decide -H 'content-type: application/json' -d "{\"directory\":\"$CORPUS\"}"
```

streams a run of the runtime's decision thinker over the corpus, one JSON line per event: the
objective is to find the statements that decide a change of a price, the palette is three
tools that call no model (list the folder, read a file, split a document into statements),
and every turn the model ranks the legal moves code computed from the artifact types: which
tool runs next or finish, which artifact it runs on, and which statements belong in the
answer. A decision call's completed line carries the whole exchange, the state the model was
shown and every distribution it answered, since a decision model produces no reasoning to
show; the last line, `workflow_complete`, carries the statements selected, the thinker's
record of every turn, and the totals. On the shipped corpus, Kev-9B on a Mac answers in four
turns and about 25 seconds for no cost: it lists the thirty files, opens the dealer portal
changelog (the one file whose first words name a price, chosen at about 0.2 over the thirty),
splits it, finishes at about 0.6 over reading on, and selects the changelog's April line about
the Kestrel 1 prices at 0.9. The page's step 8 draws the same stream as bars per turn. `GET
/decide` is the objective and the palette.

The demo's workload is the extractor: every file under a directory goes through the cheapest
tier that can read it, in parallel, under a budget. Code reads text, PDF text layers and
Office files itself; a scanned page or an image goes to a SMALL model that can see; a file
whose bytes decide nothing goes to a SMALL classifier; every text is embedded into an
in-memory index. The demo ships a corpus for it and `GET /status` reports the corpus's
absolute path on this machine as `corpus` (the page prefills its form from the same field):

```
CORPUS=$(curl -s localhost:8080/status | jq -r .corpus)
curl -s localhost:8080/extract -H 'content-type: application/json' \
  -d '{"directory":"'"$CORPUS"'","budgets":[{"amount":1.0,"currency":"USD"}]}'
```

The report has one row per file (`tier`, `chars`, the model and what its calls cost, a
`note` when something needs a person), the spend per model, the caps and the total. On the
shipped corpus of 29 files through Bedrock this is 37 model calls and a few cents. A
`budgets` entry is a cap per currency: a run that would pass it has its remaining model
calls refused at admission, each such file reporting `REFUSED` with the cap it hit, and code
still reads what code can read. Then:

```
curl -s localhost:8080/search -H 'content-type: application/json' \
  -d '{"query":"why does the headset creak","k":3}'
```

searches the index; the top hit on the shipped corpus is the scanned letter, which only the
vision tier could read.

The second demo runs on the first one's result. Every indexed document goes to a SMALL model
that lists the prices it states and what the document says about each: who pays it, whether
it is in force, proposed, a former price or a cost, and the dates the document attaches. The
distinct product names then go once to a MEDIUM model that says which names are one product.
Code does the rest: per product, audience and currency, the prices in force compete by date,
the latest is current, earlier statements of the same amount confirm it, earlier different
amounts are superseded, a change decided as a percentage becomes a price by applying it to
whatever was in force before its date, a price dated after the day the run answers for
(`asOf` in the request, else today) is scheduled rather than current, and proposals,
former prices, costs and discontinuations are set aside as what they are. A document
whose only date is in its file name is dated by it. The shipped corpus's story is written
to be answered for 1 June 2026, the day its last decided price takes effect; `GET /status`
reports that day as `corpusAsOf` and the page opens both pricing steps on it, so a run
answering for today sees the same prices as long as nothing in the corpus is dated later:

```
curl -s localhost:8080/pricing -H 'content-type: application/json' \
  -d '{"asOf":"2026-06-01","output":"pricing.json","budgets":[{"amount":5.0,"currency":"USD"}]}'
```

writes the result to `nucleo-demo/pricing.json` and returns it: `products`, each with its `series`
(one per audience and currency, with `current` and every `point` labelled `CURRENT`,
`SCHEDULED`, `CONFIRMED`, `SUPERSEDED`, `CONFLICT`, `FORMER`, `PROPOSED` or `UNDATED`, each with its
source and the words it came from, a change with the percentage and what it was applied to,
or `UNAPPLIED` with the reason), its `costs`, and `discontinuedFrom` when a document says
so; `ignored`, the mentions the arithmetic could not use and why; one row per document; and
the spend. On the shipped corpus this reads 26 documents in about a minute for a third of a
dollar, and the Kestrel 1 gravel comes out at 20 percent on its April 2026 price, decided
in minutes dated by their file name, with the April price and five earlier documents
beneath it.

The budget here is larger than the spend because a cap is judged against what every call in
flight could cost, not against the bill: 25 documents reserving a few cents of output each
commit about two dollars at once, and a cap of two refuses the last of them at admission
while the run spends a quarter. That refusal is the report's `REFUSED` row, and the point of
the cap.

With a decision model connected, the same run answers on it:

```
curl -s localhost:8080/decide-prices -H 'content-type: application/json' -d '{"asOf":"2026-06-01"}'
```

is the same doer, documents, reading tier, reconciliation and report, with the grouping of
the product names put to the decision model in place of the MEDIUM model's one call: a
verdict per pair of names, "do these two mean the same product", then per group of several
a choice of the proper name. On the shipped corpus, Kev-9B on a Mac grouped twenty names in
some twenty seconds and joined what the MEDIUM model left apart; the current prices came out
the same. The page's step 9 runs it and step 6 beside it.

`nucleo-demo/src/main/java/ai/redouble/demo/PACKAGE.md` explains what the demo configures and
where, which is deliberately almost nothing.

## After the demo: writing an agent of the person's own

`nucleo-examples` holds small programs, each a `main` in a package of its own with a
`PACKAGE.md` explaining it, that run on the same credentials the demo used. Start the
person's own code from the one closest to the job, copy it, and change the domain:

| The job | Start from |
|---|---|
| Ask a model one question from code | `ai.redouble.examples.hello` |
| Wrap an existing operation so a model can call it | `ai.redouble.examples.tool` |
| Let a model decide which operations to call | `ai.redouble.examples.agent` |
| Orchestrate known steps in Java, in parallel | `ai.redouble.examples.doer` |
| Pass records between agents unaltered | `ai.redouble.examples.artifacts` |
| Keep an agent inside one customer, tenant or case | `ai.redouble.examples.scope` |
| Bind one flow to several axes, narrowing one partway | `ai.redouble.examples.scopes` |
| Cap what a workflow may spend | `ai.redouble.examples.cap` |
| Classify or route with a decision model | `ai.redouble.examples.decision` |
| Find the cheapest grade that does the job | `ai.redouble.examples.benchmark` |
| Benchmark a thinker with a rubric and a score in code | `ai.redouble.examples.scoring` |
| Offer a tool to outside MCP clients | `ai.redouble.examples.mcpserver` |
| Call any MCP server's tools | `ai.redouble.examples.mcpclient` |
| Depend on a remote MCP tool from your workflows | `ai.redouble.examples.mcpwrap` |

Each example is one `main` with its question written in: run it from the IDE, change the
question in the code, run it again.

The documentation reads in one order from `nucleo-docs/CONTENTS.md`; the published site,
[docs.redouble.ai](https://docs.redouble.ai/), carries the same order as
[llms.txt](https://docs.redouble.ai/llms.txt), and every page in one file as
[llms-full.txt](https://docs.redouble.ai/llms-full.txt).

## What not to do at any step

- Do not edit the runtime to get past a refusal. A refusal names what to provide; provide it
  or report it to the person.
- Do not hardcode a model id anywhere in code. Model ids live in `models.json` and nowhere else.
- Do not skip tests to save time and then report the build as green.
- Do not hand-roll native support for a third-party dependency. For a Quarkus native build,
  never hand-write `reflect-config.json` / `resource-config.json` / `reachability-metadata.json`
  for a dependency, and never add extra jars to force one to link. Use the dependency's existing
  Quarkus or Quarkiverse extension, or keep the dependency out of the native build (JVM-only
  behind a maven profile, the way `nucleo-demo-poi` keeps POI/PDFBox out of native). Check for an
  extension first. This is absolute.
