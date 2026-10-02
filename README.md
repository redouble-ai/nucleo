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

## Quickstart

The written walk-through is [Hello, model](https://docs.redouble.ai/nucleo/0.1/examples-hello.html),
the first page of the documentation's examples, which go on to a first tool, agent and
doer. For a coding agent, [AGENTS.md](AGENTS.md) is the same start written to it: open
this repository in the agent and ask it to read `AGENTS.md`. Every step there is a command
with a checkable result, so it works as a by-hand quickstart too. What follows is the short
form.

Java 25, with Maven or Gradle. The build targets Java 25 and the artifacts carry class
files for it, so an older JDK cannot load them.

The runtime comes from Maven Central under the group `ai.redouble`: import the BOM, then
depend on `nucleo-core` and the provider modules of the accounts you hold
(`nucleo-provider-anthropic`, `nucleo-provider-openai`, `nucleo-provider-bedrock`,
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

The same with Gradle:

```kotlin
dependencies {
    implementation(platform("ai.redouble:nucleo-bom:0.1"))
    implementation("ai.redouble:nucleo-core")
    implementation("ai.redouble:nucleo-provider-anthropic")
}
```

A first question to a model, with the credential of that provider exported in the
environment (`ANTHROPIC_API_KEY` for the one above; [AGENTS.md](AGENTS.md) names every
provider's variables):

```java
JobDispatcher dispatcher = JobDispatcher.getInstance();
dispatcher.start();
try {
    QuickLLMQuestionInput question = new QuickLLMQuestionInput();
    question.setQuestion("Who is speaking, and where are they going?");
    question.setContext("Call me Ishmael. Some years ago, having little money in my purse,"
            + " I thought I would sail about a little and see the watery part of the world.");
    question.setGrade(Grade.SMALL);
    QuickLLMQuestionTool tool = new QuickLLMQuestionTool(Job.workflow("you", "hello"));
    tool.setInput(question);
    QuickLLMQuestionOutput answer = dispatcher.submit(tool).get();
    System.out.println(answer.getAnswer());
}
finally {
    dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
}
```

That is [HelloModel.java](nucleo-examples/src/main/java/ai/redouble/examples/hello/HelloModel.java)
from `nucleo-examples`, runnable as it stands and explained line by line on the
[Hello, model](https://docs.redouble.ai/nucleo/0.1/examples-hello.html) page. The demo,
set up by [AGENTS.md](AGENTS.md), runs against your own account's models:

```
mvn install -DskipTests
cd nucleo-demo
java -jar target/nucleo-demo.jar discover
java -jar target/nucleo-demo.jar
```

and its page is at http://localhost:8080. It runs the same work on two hosts - a Spring
Boot process and a Quarkus one, the Quarkus host on the JVM or compiled to a native image:
ask a model a question, watch an agent choose its own tools and skills, push a folder of
documents through parallel extraction, race one workload across every model your account
can call - judged blind - to see which quality tier the job actually needs, and run an
agent on a decision model, a third kind of model that never writes a word and only ranks
the options code puts in front of it
(*[deciding](nucleo-core/src/main/java/ai/redouble/nucleo/tools/deciding/PACKAGE.md)*).
It serves its own documentation at `/docs`.

Two extensions, `nucleo-ext-lit` (literature search: PubMed, PMC, bioRxiv) and
`nucleo-ext-patent` (patent search: the EPO and the USPTO), and the `nucleo-provider-systemone`
decision-model provider ship in this repository and on Central as optional modules: tools
and a provider built on the runtime, which nothing in the runtime depends on.

## What Nucleo gives you

| You need                                              | Nucleo gives you                                                  | Read                                                                                                                                                      |
|-------------------------------------------------------|-------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| A model's answer as a Java object                     | a `Tool` that calls the model, typed in and out                   | [Hello, model](https://docs.redouble.ai/nucleo/0.1/examples-hello.html)                                                                                   |
| Your code, callable by a model                        | `Tool`: the Java you already trust, with a typed input and output | [Your first tool](https://docs.redouble.ai/nucleo/0.1/examples-tool.html)                                                                                 |
| An agent that chooses among tools                     | `Thinker`                                                         | [Your first agent](https://docs.redouble.ai/nucleo/0.1/examples-agent.html), [The kinds of thinker](https://docs.redouble.ai/nucleo/0.1/tools-thinking.html) |
| An agent that carries a multi-step task through       | `Doer`                                                            | [Your first doer](https://docs.redouble.ai/nucleo/0.1/examples-doer.html)                                                                                 |
| A boundary a job cannot leave, a rule in code         | `Scope`, `Guardrail`                                              | [An agent that stays inside its case](https://docs.redouble.ai/nucleo/0.1/examples-scope.html), [Writing a guardrail](https://docs.redouble.ai/nucleo/0.1/guardrails.html) |
| Data that crosses agents byte-identical               | `Artifact`                                                        | [Data the model cannot alter](https://docs.redouble.ai/nucleo/0.1/examples-artifacts.html)                                                                |
| Hundreds of agents in one JVM, inside provider quotas | `JobDispatcher` on virtual threads, admission and rate limiting   | [Admission](https://docs.redouble.ai/nucleo/0.1/harness-admission.html), [A dollar cap on a workflow](https://docs.redouble.ai/nucleo/0.1/examples-cap.html) |
| The record of what every job did                      | the event stream                                                  | [What a running job reports](https://docs.redouble.ai/nucleo/0.1/events.html), [Logs, traces, metrics and cost](https://docs.redouble.ai/nucleo/0.1/harness-observability.html) |
| Tools served by an MCP server                         | `MCPConnector`                                                    | [Calling an MCP server](https://docs.redouble.ai/nucleo/0.1/examples-mcpclient.html)                                                                      |
| Spring Boot, Quarkus                                  | `nucleo-spring-boot-starter`, `nucleo-quarkus`                    | [Running inside Spring Boot](https://docs.redouble.ai/nucleo/0.1/spring.html), [Running inside Quarkus](https://docs.redouble.ai/nucleo/0.1/quarkus.html) |

## Why Nucleo

The systems you build already encode how the business works, and wherever the code could
not decide, it stopped and prompted a human. Nucleo lets that prompt go to a model without
the case ever leaving your process: your code stays deterministic, the model exercises
judgment inside the boundary the application set, and hundreds of such agents run on
virtual threads inside the JVM you already run. [Why Nucleo](PHILOSOPHY.md) makes the case
in full: what the runtime keeps under the application's control, and what that buys you.

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
nothing behind it. The record of what an agent did is part of that: the runtime publishes
every job, tool call and model call, in order, with what went in and what came out, as an
event stream the application subscribes to, logs, traces or exports while the process
runs. Redouble AI's commercial platform, Silverlake, builds on Nucleo for enterprise
deployments: the store that keeps those records and the conversations after the process
exits, and fleet-level controls. The boundary is the package name - `ai.redouble.nucleo`
is the open runtime.

## Requirements

| Nucleo | Java        | Spring Boot | Quarkus |
|--------|-------------|-------------|---------|
| 0.1    | 25 or later | 4.x         | 3.x     |

Nucleo uses virtual threads and the Java 25 language throughout; no older Java is planned.
Java 25 is a long-term support release, and the first one in which virtual threads, which
the runtime is built on, reached maturity. Nucleo plans long-term support for Java 25. The artifacts are consumed
with Maven or Gradle.

## Contributing

Nucleo is developed by Redouble AI, which keeps its design coherent from one release to the
next. Bug reports and questions are always welcome, small fixes come in as pull requests,
and anything that changes the API or the design starts as an issue, where the approach is
agreed first. [CONTRIBUTING.md](CONTRIBUTING.md) says exactly which is which, before you
write code.

A vulnerability is reported privately, through GitHub's
[report form](https://github.com/redouble-ai/nucleo/security/advisories/new) or to
oss@redouble.ai, never as a public issue: [SECURITY.md](SECURITY.md).

## License

Apache-2.0. Copyright 2024-present Redouble AI, Inc. Authors.

**Does Redouble AI have patents related to Nucleo?** Yes, we have IP. No, we are not
playing games with the licence. Redouble AI has filed patent applications on technology in
this area. Nucleo is released under the Apache License 2.0, its patent provisions included,
and that licence alone governs what you may do with Nucleo: use it, change it, ship it,
sell with it.
