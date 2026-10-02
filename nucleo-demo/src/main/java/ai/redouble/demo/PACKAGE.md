# Nucleo demo

The demo is a small application that puts the whole runtime to work on your own model
account, in one web page. Each of its nine steps runs something real - a single model call,
an agent, a folder of documents read in parallel under a spending cap, a benchmark of every
model you can call, an agent on a decision model - and shows what happened and what it cost.
It is meant to be run first, before any code of yours: what the documentation describes, the
demo shows running. [Set up and run the demo](../../../../../../../AGENTS.md) walks through
getting it started; this page says what each step shows and where the documentation explains
it.

The page opens at the root of the running process, and the same documentation you are
reading is served beside it at `/docs`.

## One engine, two hosts

Everything the demo does lives in one module, `nucleo-demo-engine`: the agents, the
workflows, the index, the documents it reads, the page, and the logic behind every endpoint
the page calls. Two host applications serve it and add only their framework's wiring:

- `nucleo-demo`, this module, a Spring Boot application built on the Nucleo starter
  ([Spring Boot](../../../../../../../nucleo-spring-boot-starter/src/main/java/ai/redouble/nucleo/spring/PACKAGE.md)).
  It is the shape an application of yours copies: the runtime starts and stops with the
  application, credentials are Spring properties, and each endpoint submits a job and returns
  its result.
- `nucleo-demo-quarkus`, the same demo on Quarkus, on the JVM or compiled to a native image
  ([The Quarkus host](../../../../../../../nucleo-demo-quarkus/src/main/java/ai/redouble/demo/quarkus/PACKAGE.md)).

Because the logic is in the engine, the two hosts answer every endpoint the same way.

## The steps

### 1. Credentials and models

You connect a provider, by exporting its credential before the demo starts or by pasting it
into the page, and the page shows every model the runtime can call. A credential pasted into
the page is held by the running process only, and is tested at once: where its provider can
list the account's models, it makes that one call and shows the count or the failure ([Credentials](../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/secrets/PACKAGE.md)).

The table below the connections is the model catalog, the file that tells the runtime which
models exist, what they cost and which one serves each grade of work
([The catalog, grades and the picker](../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/models/PACKAGE.md)).
Discover models fills it from your account's own listings, pinging each model once, which
costs a few cents
([Discovering an account's models](../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/models/discovery/PACKAGE.md)).
In the table you set each grade's order, the models it prefers from first to last, by
dragging rows; you choose the embeddings model and the decision model, move a model to
another grade, disable one, and type a model's list prices. Each change is written to the
catalog file and takes effect at once, and each grade's line says which model now serves a
request of that grade, one carrying images and one carrying documents. A model the discovery
had to infer, its grade and prices from a classifier's knowledge or an older relative, is
marked unverified with the discovery's note on hover, until you edit its grade or a price or
press confirm.

### 2. Ask a quick question

One call to a model at the grade you choose, answered with the catalog entry that served it,
the model behind it and the price of the call. With no provider connected, the answer is the
runtime's refusal naming what to provide. This is
[Hello, model](../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/hello/PACKAGE.md)
behind a web form.

### 3. Run the agent

An agent with two tools of its own, the current time and date arithmetic, a web-fetch tool
it may ask for, and every skill the process found on its classpath
([Skills and skilljars](../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/prompt/skill/PACKAGE.md)).
Its model decides which tool to call and which skill to admit, and the page draws the run as
it happens: each model turn, the tool calls it decided on with their arguments and results,
and what each turn cost. The query "How many days until the end of the year? Briefly." takes
the date from a tool, and the word "briefly" admits the `concise-answers` skill, so the answer
comes back one line long. The agent is written the way
[Your first agent](../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/agent/PACKAGE.md)
writes one ([Tools, thinkers and doers](../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/tools/PACKAGE.md)),
and the drawing is made from the events every job publishes as it runs
([Events and the message bus](../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/events/PACKAGE.md)).

### 4. Read a directory

Every file of a folder is read into an index, in parallel, each by the cheapest means that
works: code reads text, source, data, Office documents and PDFs with a text layer, which
costs nothing; a model that can see reads scans and photographs; a small model judges the
files nothing else could place. Every text is then embedded for search. The run is a doer,
plain Java that composes tools
([Your first doer](../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/doer/PACKAGE.md)),
and every file is a job of its own, admitted against the provider's rate limits before any
call is sent ([Admission](../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/admission/PACKAGE.md)).
The budget you enter is a spend cap on the whole run: once it is spent, every further model
call is refused before it is made, and the files code can read are still read
([Observers, cost and the spend cap](../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/observability/PACKAGE.md)).
Set the budget to one cent to watch it happen.

The demo ships a folder to read: the shared drive of Halcyon Bicycle Works, an invented
company, some thirty files in every format a small company accumulates, from price lists and
meeting minutes to a scanned letter and a photographed whiteboard. The page shows its path
wherever the demo runs, and that folder is the one the run reads: no request names a folder,
so nothing reachable over HTTP chooses what the process opens. To read a different folder,
change `DemoCorpus` in nucleo-demo-engine.

### 5. Search what was read

A question is embedded the way the documents were and matched against the index by meaning.
On the shipped folder, "why does the helmet squeak" finds the documents about a creaking
headset, although none of them says "helmet" or "squeak".

### 6. Find the current prices

What each product costs right now, from the documents step 4 read. A small model lists the
prices each document states, all documents at once; a medium model says which product names
mean the same product; and plain Java decides, as of the day you choose, which price is
current, which is scheduled, which is superseded and which statements conflict. Every price
carries the sentence it came from. The models' answers arrive as Java objects
([Answers as Java objects](../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/schema/PACKAGE.md)),
and the decision that must be right every time is code.

### 7. Benchmark the agent

Step 3's query answered by the agent on every model you can call, from the smallest grade to
the strongest, and each answer scored blind by the strongest model. The table shows which
models answered well and what each answer cost, and it proposes an order for each grade,
cheapest good model first, which one click writes into the catalog of step 1
([Measuring the grade a job needs](../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/tools/benchmark/PACKAGE.md),
[Racing a grade](../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/benchmark/PACKAGE.md)).

### 8. Run an agent on a decision model

A decision model writes no text: it reads a state and a set of options and answers with a
probability for each
([What a decision model is](../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/tools/deciding/PACKAGE.md)).
Here one runs a whole agent over the shipped folder, choosing at every turn which file to
open, which document to split into statements, and which statements decide a price change.
Its tools call no model, so on a decision model served from your own machine the run costs
nothing. [The decision agent](../../../../../../../nucleo-demo-engine/src/main/java/ai/redouble/demo/decide/PACKAGE.md)
describes it.

### 9. Find the current prices, grouped by a decision model

Step 6 again, with the question of which product names mean one product put to the decision
model in place of the medium model. The current prices come out the same, and the grouping
costs nothing on a decision model of your own.

## What holds on every step

- Every job runs under a workflow owned by the person asking. The demo has no login, so the
  owner is `anonymous`; a host with authentication puts its user there.
- A person is waiting behind every request, so every job the demo submits retries a failed
  call to the provider once (`DemoPolicy.UPSTREAM_RETRIES`, where the runtime's default is
  three), and a provider that keeps failing shows as a failed row within a minute.
- A refusal says what to provide, a credential or a model, in the runtime's own words.

## The catalog file

The runtime reads its catalog off the classpath, so the demo keeps it where an application
keeps `logback.xml`: in its module's resources, `nucleo-demo/src/main/resources/models.json`
for this host (`DemoHome`). The file is visible in the project, and every build carries it
into the jar. The demo finds its module from where its own code runs, so it is the same file
from the IDE or from the jar, whatever directory the process was started in. The discovery
command writes the same file. On the first start there is no file, and the demo runs on the
models the provider artifacts ship with, saying so: the file appears from your first action
alone. Discover models writes your account's own into it, and an edit in the models table
writes the shipped models first, so the edit has a file to land in; no provider is called
for that. Every change in the models table is written to it and takes effect at once, before
any rebuild. A copy of the jar running outside the module runs on the catalog its build
carried, which the page shows read-only. To run the demo on a catalog kept elsewhere, name that
file with `-Dnucleo.models`; the demo then leaves the choice alone. The file is the account's,
so this repository ignores it.

## How it works inside

Every endpoint with its request and response, the streams the page draws from, the rules of
the catalog edits, the pricing arithmetic, the command-line discovery and the skills setup are
in [Inside the demo](DEMO_INTERNALS.md), for those working on the demo itself.
