# Nucleo

Intelligence as a Java component.

Nucleo is a runtime for AI agents that live inside your Java application: in-stack,
in-process, inside your own security perimeter. An agent is worth exactly as much as the
systems it can act through - the transactions, the entitlements, the audit trails - and
those are the systems nobody should have to hand to an external runtime. Nucleo runs the
agents where that software already lives.

Authoring frameworks give an engineer a way to write an agent. Nucleo is the layer those
agents run in - it sits with them the way an application server sits with a web framework,
and code written with any of them can run inside one of its tools.

## Philosophy

The systems you build already encode how the business works: the validations, the
entitlements, the edge cases, the audit. And nobody knows better than you where their
judgment calls live, because you built those seams: the review queue, the approval step,
the exception workbasket, the branch that routes a case to a human specialist. Wherever the code
could not decide, it stopped and prompted a human.

We built Nucleo so that this prompt can go to a model, without the case ever leaving your
process. You wrap
the code you already trust - the data access, the service call, the validation - as typed
`Tool`s, hand a set of them to a `Thinker` with a prompt stating its objective, and that
seam in the workflow can exercise judgment: the model decides which tools to call and what
to conclude, much like a human decides how to interact with a user interface; your code
does what it always did, and everything that can be deterministic stays deterministic,
because code is cheaper, faster, testable, and already right.

The AI engineering is the runtime's job. Everything between "call the model" and a typed
answer landing in your code - the provider APIs and their differences, the malformed
replies, the retries, the bookkeeping - is carried for you, out of your code and out of
sight until you choose to look, but fully customizable, extendable and moldable when you
do. You use the model the way you use a database: a component you build on, whose
internals are somebody else's job.

And it asks nothing of the rest of your architecture. Your data and your internal logic
stay unexposed: there is no hosted runtime to feed and no fleet of MCP servers publishing
your data access to the network. There is no parallel stack: agents deploy inside the
application they serve, through the pipeline you already run. There is no new profession
to hire for: the engineer who knows the business logic is the qualified author, on day one.

## The process stays in charge

Enterprises have run non-deterministic actors inside deterministic processes forever; they
are called people, and systems govern their input with types, validation and permissions.
Nucleo governs the model the same way, in code, and here is what that buys you:

- **Your data cannot be garbled.** An account number, a dosage, a table of results comes
  out of a chain of agents byte-identical to what your tool produced. Models decide what
  to pass along and are structurally unable to alter it
  (*[artifacts](nucleo-core/src/main/java/ai/redouble/nucleo/harness/artifacts/PACKAGE.md)*).
- **An agent stays inside its case.** You set what a workflow may touch when it starts,
  and the boundary holds in code: no prompt, no injected instruction, no conversational
  cleverness widens it
  (*[scope](nucleo-core/src/main/java/ai/redouble/nucleo/tools/guardrails/PACKAGE.md)*).
- **Your rules stay deterministically enforced.** Any check you can write in Java runs before a tool executes,
  under the caller's identity. Policy lives where the model cannot negotiate with it
  (*[guardrails](nucleo-core/src/main/java/ai/redouble/nucleo/guardrails/PACKAGE.md)*).
- **You never parse model text.** An answer arrives as the Java object you declared,
  validated like any user input; a reply that does not conform is corrected before your
  code ever sees it.
- **"What did it do?" always has an answer.** Every model call and tool call is recorded
  in order, with what went in and what came out, per workflow. Debugging an agent is
  reading a record, like debugging anything else.
- **Failures behave.** A tool that throws tells the agent what went wrong and whether
  trying again can help, so a workflow degrades the way you designed instead of the way
  the stack trace fell.

## Hundreds of agents inside the JVM you already run

Agents are greedy workloads: they hold things for minutes that everything else holds for
milliseconds, they arrive in bursts, and their ceiling is a provider quota rather than
your hardware. Nucleo runs them beside your application anyway, safely: hundreds of
concurrent agents on virtual threads, each acquiring everything it needs before it runs or
waiting its turn holding nothing - the same discipline you already trust in a connection
pool, applied to everything a job touches. Provider rate limits are respected before a
request is sent rather than apologized for after, so your application keeps its capacity
and the agents queue for theirs.

## Getting started

[AGENTS.md](AGENTS.md) sets up the demo and is written to your coding agent: open this
repository in the agent and ask it to read `AGENTS.md`. Every step is a command with a
checkable result, so it works as a by-hand quickstart too.

The demo runs the same work on two hosts - a Spring Boot process and a Quarkus one, the
Quarkus host on the JVM or compiled to a native image - through the runtime against your own
account's models: ask a model a question, watch an agent choose its own tools and skills,
push a folder of documents through parallel extraction, race one workload across every
model your account can call - judged blind - to see which quality tier the job actually
needs, and run an agent on a decision model, a third kind of model that never writes a word
and only ranks the options code puts in front of it
(*[deciding](nucleo-core/src/main/java/ai/redouble/nucleo/tools/deciding/PACKAGE.md)*).
It serves its own documentation at `/docs`.

After the demo, `nucleo-examples` is where to start writing: a first question to a model in
twenty lines
(*[hello](nucleo-examples/src/main/java/ai/redouble/examples/hello/PACKAGE.md)*), then a
first tool, agent and doer, and one small program per thing the runtime keeps under the
application's control.

In your own build, the runtime comes from Maven Central under the group `ai.redouble`:
import the BOM, then depend on `nucleo-core` and the provider modules of the accounts you
hold (`nucleo-provider-anthropic`, `nucleo-provider-openai`, `nucleo-provider-bedrock`,
`nucleo-provider-bedrock-anthropic`, `nucleo-provider-systemone`). A Spring Boot
application adds `nucleo-spring-boot-starter`, a Quarkus application `nucleo-quarkus`.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>ai.redouble</groupId>
            <artifactId>nucleo-bom</artifactId>
            <version>0.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency>
        <groupId>ai.redouble</groupId>
        <artifactId>nucleo-core</artifactId>
    </dependency>
    <dependency>
        <groupId>ai.redouble</groupId>
        <artifactId>nucleo-provider-anthropic</artifactId>
    </dependency>
</dependencies>
```

## Documentation

The documentation is at [docs.redouble.ai](https://docs.redouble.ai/), one version per
release, with the full javadoc.

Every package carries a `PACKAGE.md` that is the contract of record for what the package
does, readable in the tree where the code lives.
[nucleo-docs/CONTENTS.md](nucleo-docs/CONTENTS.md) puts them in reading order, from a first
look through writing, governing, running, observing and hosting agents, and the site follows
that order. `mvn site` (after a build) generates the same site from your checkout under
`src/main/docs`: open `src/main/docs/index.html`. The demo serves it at `/docs`.

## The runtime and the platform

Nucleo is Apache-2.0 and complete on its own: everything above ships here and works with
nothing behind it. Redouble AI's commercial platform, Silverlake, builds on Nucleo for
enterprise deployments: durable run records for every job and model call, conversation
persistence stores, and fleet-level controls. The boundary is the package name -
`ai.redouble.nucleo` is the open runtime.

## Requirements

Java 25 and Maven.

## License

Apache-2.0. Copyright 2024-present Redouble AI, Inc. Authors.
