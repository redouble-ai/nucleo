# Nucleo documentation

Nucleo runs AI agents inside a Java application. These pages are in the order a reader
meets it: what it is, the demo running on your own account, a set of examples, and then one
part per question an engineer asks on the way to shipping an agent - how to write one, how
the application keeps control of it, which model serves it, what a model call carries, how
many can run at once, how to see what they did, and how to host the runtime. Each page
builds on the ones before it, and the links at the bottom of every page walk this order.

- [Overview](../README.md)
- [Why Nucleo](../PHILOSOPHY.md)
- [Contents](CONTENTS.md)
- [Set up and run the demo](../AGENTS.md)

## Examples

Small programs to read and copy from, each a main in nucleo-examples: a first question to a
model, a first tool, agent and doer, then one program for each thing the runtime keeps under
the application's control. The code on every page is the code that compiles; the build
fails when the two differ.

- [Hello, model](../nucleo-examples/src/main/java/ai/redouble/examples/hello/PACKAGE.md)
- [Your first tool](../nucleo-examples/src/main/java/ai/redouble/examples/tool/PACKAGE.md)
- [Your first agent](../nucleo-examples/src/main/java/ai/redouble/examples/agent/PACKAGE.md)
- [Your first doer](../nucleo-examples/src/main/java/ai/redouble/examples/doer/PACKAGE.md)
- [Data the model cannot alter](../nucleo-examples/src/main/java/ai/redouble/examples/artifacts/PACKAGE.md)
- [An agent that stays inside its case](../nucleo-examples/src/main/java/ai/redouble/examples/scope/PACKAGE.md)
- [Overlapping scopes](../nucleo-examples/src/main/java/ai/redouble/examples/scopes/PACKAGE.md)
- [A dollar cap on a workflow](../nucleo-examples/src/main/java/ai/redouble/examples/cap/PACKAGE.md)
- [One decision](../nucleo-examples/src/main/java/ai/redouble/examples/decision/PACKAGE.md)
- [Racing a grade](../nucleo-examples/src/main/java/ai/redouble/examples/benchmark/PACKAGE.md)
- [Any thinker in a benchmark](../nucleo-examples/src/main/java/ai/redouble/examples/scoring/PACKAGE.md)
- [Your tools over MCP](../nucleo-examples/src/main/java/ai/redouble/examples/mcpserver/PACKAGE.md)
- [Calling an MCP server](../nucleo-examples/src/main/java/ai/redouble/examples/mcpclient/PACKAGE.md)
- [An MCP tool wrapped as your own](../nucleo-examples/src/main/java/ai/redouble/examples/mcpwrap/PACKAGE.md)

## Writing an agent

You wrap operations you already trust as typed tools and hand a set of them to a thinker,
an agent whose model decides which tool to call next; a doer composes tools and thinkers in
plain Java. The answer comes back as the Java object you declared, and data a tool produced
travels between agents unaltered.

- [Tools, thinkers and doers](../nucleo-core/src/main/java/ai/redouble/nucleo/tools/PACKAGE.md)
  - [The kinds of thinker](../nucleo-core/src/main/java/ai/redouble/nucleo/tools/thinking/PACKAGE.md)
  - [Which tools an agent is offered](../nucleo-core/src/main/java/ai/redouble/nucleo/tools/registry/PACKAGE.md)
- [Answers as Java objects](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/schema/PACKAGE.md)
- [Artifacts: data the model cannot alter](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/artifacts/PACKAGE.md)
  - [Artifact tools](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/artifacts/tools/PACKAGE.md)
- [Prompts](../nucleo-core/src/main/java/ai/redouble/nucleo/prompt/PACKAGE.md)
  - [Prompt sources](../nucleo-core/src/main/java/ai/redouble/nucleo/prompt/sources/PACKAGE.md)
  - [Skills and skilljars](../nucleo-core/src/main/java/ai/redouble/nucleo/prompt/skill/PACKAGE.md)
- [Built-in tools](../nucleo-core/src/main/java/ai/redouble/nucleo/tools/builtin/PACKAGE.md)

## The process stays in charge

The application decides what an agent may touch, and the model cannot widen it: a scope
fixed when the work starts, guardrails that run as Java before every tool call under the
caller's identity, and failures that tell the model what went wrong and whether trying
again can help.

- [Scope and the trust boundary](../nucleo-core/src/main/java/ai/redouble/nucleo/tools/guardrails/PACKAGE.md)
- [Writing a guardrail](../nucleo-core/src/main/java/ai/redouble/nucleo/guardrails/PACKAGE.md)
- [Failures that behave](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/errors/EXCEPTIONS.md)
  - [When a job runs again](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/errors/retry/PACKAGE.md)
  - [HTTP errors as typed failures](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/errors/http/PACKAGE.md)

## Models

Code asks for a grade of model, a rung on a ladder from MICRO to MEGA, and the deployment's
catalog says which entry serves it, at what price and through which provider. A discovery
run writes the catalog from the account's own listings, and a benchmark measures which
grade a job needs.

- [The catalog, grades and the picker](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/models/PACKAGE.md)
  - [Discovering an account's models](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/models/discovery/PACKAGE.md)
- [Measuring the grade a job needs](../nucleo-core/src/main/java/ai/redouble/nucleo/tools/benchmark/PACKAGE.md)
- [Anthropic](../nucleo-provider-anthropic/src/main/java/ai/redouble/nucleo/providers/anthropic/PACKAGE.md)
- [Amazon Bedrock](../nucleo-provider-bedrock/src/main/java/ai/redouble/nucleo/providers/bedrock/PACKAGE.md)
  - [Claude on Bedrock through the Anthropic SDK](../nucleo-provider-bedrock-anthropic/src/main/java/ai/redouble/nucleo/providers/bedrock/anthropic/PACKAGE.md)
- [OpenAI](../nucleo-provider-openai/src/main/java/ai/redouble/nucleo/providers/openai/PACKAGE.md)

## Inside a model call

What one call to a model sends and receives: the conversation and its content blocks, how
the tools and the objective are shown to the model, prompt caching, compaction when a
conversation outgrows its window, and the client layer that turns all of it into each
vendor's request, admitted against the provider's rate limits before it is sent.

- [Conversations: what every call carries](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/conversation/PACKAGE.md)
  - [When a conversation outgrows its window](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/conversation/compaction/PACKAGE.md)
- [Model clients and rate limits](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/llm/PACKAGE.md)
  - [How each part of a message reaches a provider](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/llm/encode/PACKAGE.md)

## Decision models

A third kind of model beside chat and embeddings: it reads a state and typed questions and
answers with a probability for each option, writing no text. Code puts the options in front
of it, and a whole agent can run on one.

- [What a decision model is](../nucleo-core/src/main/java/ai/redouble/nucleo/tools/deciding/PACKAGE.md)
- [The decision wire](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/decision/PACKAGE.md)
- [Connecting a decision model](../nucleo-provider-systemone/src/main/java/ai/redouble/nucleo/providers/systemone/PACKAGE.md)

## Hundreds of agents in one JVM

Every piece of work is a job on a virtual thread. A job declares everything it needs and is
admitted with all of it at once, or waits holding nothing, and a provider's rate limits are
respected before a request is sent. The same admission covers the connections a job holds:
HTTP, a DataSource, Hibernate.

- [The job runtime](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/PACKAGE.md)
- [Admission](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/admission/PACKAGE.md)
  - [Why admission has this shape](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/admission/ADMISSION.md)
- [The HTTP connection pool](../nucleo-core/src/main/java/ai/redouble/nucleo/http/PACKAGE.md)
- [Connections from a DataSource](../nucleo-core/src/main/java/ai/redouble/nucleo/jdbc/PACKAGE.md)
- [Hibernate sessions](../nucleo-hibernate/src/main/java/ai/redouble/nucleo/hibernate/PACKAGE.md)

## Seeing what happened

Every job publishes typed events on a message bus as it runs. Observers turn them into log
lines, spans, metrics, and a cost ledger that can cap what a workflow spends, and nothing an
observer does slows a job.

- [Events: what a running job reports](../nucleo-core/src/main/java/ai/redouble/nucleo/events/PACKAGE.md)
  - [Heartbeats: work that runs later](../nucleo-core/src/main/java/ai/redouble/nucleo/events/heartbeat/PACKAGE.md)
  - [Retry events: a job running again](../nucleo-core/src/main/java/ai/redouble/nucleo/events/retry/PACKAGE.md)
- [Logs, traces, metrics and cost](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/observability/PACKAGE.md)
  - [Why observability has this shape](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/observability/OBSERVABILITY.md)
- [Reporting progress from a job](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/PROGRESS_GUIDELINES.md)

## Hosting Nucleo

The runtime inside an application: one configurator for every setting, credentials from the
store the deployment already uses, and the Spring Boot and Quarkus integrations that bind
the dispatcher's lifecycle to the container's.

- [Settings: where to tune anything](../nucleo-core/src/main/java/ai/redouble/nucleo/PACKAGE.md)
- [Where credentials come from](../nucleo-core/src/main/java/ai/redouble/nucleo/secrets/PACKAGE.md)
- [Running inside Spring Boot](../nucleo-spring-boot-starter/src/main/java/ai/redouble/nucleo/spring/PACKAGE.md)
- [Running inside Quarkus](../nucleo-quarkus/src/main/java/ai/redouble/nucleo/quarkus/PACKAGE.md)

## Beyond the process

For a tool that lives outside the process, and for a tool of this one offered to an outside
client: the MCP client with its transports and authorization, the MCP server with its
schema dialects, and the chat protocol a host exposes to a frontend.

- [Using tools from MCP servers](../nucleo-core/src/main/java/ai/redouble/nucleo/mcp/PACKAGE.md)
  - [Connecting to an MCP server as an agent](../nucleo-core/src/main/java/ai/redouble/nucleo/mcp/auth/PACKAGE.md)
- [Offering your tools over MCP](../nucleo-core/src/main/java/ai/redouble/nucleo/mcp/server/PACKAGE.md)
  - [Schema dialects for MCP clients](../nucleo-core/src/main/java/ai/redouble/nucleo/mcp/server/SERIAL.md)
- [Building a chat endpoint](../nucleo-core/src/main/java/ai/redouble/nucleo/chat/PACKAGE.md)
  - [Chat frames on the wire](../nucleo-core/src/main/java/ai/redouble/nucleo/chat/messages/PACKAGE.md)

## Extensions

Tool sets for particular domains, each in an artifact of its own.

- [Literature search: PubMed and bioRxiv](../nucleo-ext-lit/src/main/java/ai/redouble/nucleo/ext/lit/PACKAGE.md)
- [Patents: EPO Open Patent Services](../nucleo-ext-patent/src/main/java/ai/redouble/nucleo/ext/patent/epo/PACKAGE.md)
- [Patents: USPTO Open Data Portal](../nucleo-ext-patent/src/main/java/ai/redouble/nucleo/ext/patent/usptoodp/PACKAGE.md)

## The demo, end to end

The demo application puts the parts above to work: what each of its steps shows, the
decision agent, and the Quarkus host that serves the same engine on the JVM or as a native
image.

- [The demo, step by step](../nucleo-demo/src/main/java/ai/redouble/demo/PACKAGE.md)
  - [An agent on a decision model](../nucleo-demo-engine/src/main/java/ai/redouble/demo/decide/PACKAGE.md)
  - [The demo on Quarkus](../nucleo-demo-quarkus/src/main/java/ai/redouble/demo/quarkus/PACKAGE.md)

## Inside the runtime

For people working on Nucleo itself: how each part does what its guide describes, in the
same order as the guides. A reader using Nucleo never needs these pages.

- [Inside tools, thinkers and doers](../nucleo-core/src/main/java/ai/redouble/nucleo/tools/TOOLS_INTERNALS.md)
  - [Inside the thinker families](../nucleo-core/src/main/java/ai/redouble/nucleo/tools/thinking/TOOLS_THINKING_INTERNALS.md)
  - [Inside palettes and the tool registry](../nucleo-core/src/main/java/ai/redouble/nucleo/tools/registry/TOOLS_REGISTRY_INTERNALS.md)
- [Inside the serializer](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/schema/SERIALIZER.md)
- [Inside artifacts](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/artifacts/HARNESS_ARTIFACTS_INTERNALS.md)
  - [Inside the artifact tools](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/artifacts/tools/HARNESS_ARTIFACTS_TOOLS_INTERNALS.md)
- [Inside prompts](../nucleo-core/src/main/java/ai/redouble/nucleo/prompt/PROMPT_INTERNALS.md)
  - [Inside skills](../nucleo-core/src/main/java/ai/redouble/nucleo/prompt/skill/PROMPT_SKILL_INTERNALS.md)
- [Inside the trust boundary](../nucleo-core/src/main/java/ai/redouble/nucleo/tools/guardrails/TOOLS_GUARDRAILS_INTERNALS.md)
- [Inside guardrail enforcement](../nucleo-core/src/main/java/ai/redouble/nucleo/guardrails/GUARDRAILS_INTERNALS.md)
- [Inside the exception hierarchy](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/errors/HARNESS_ERRORS_INTERNALS.md)
  - [Inside retry signals](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/errors/retry/HARNESS_ERRORS_RETRY_INTERNALS.md)
  - [Inside HTTP status exceptions](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/errors/http/HARNESS_ERRORS_HTTP_INTERNALS.md)
- [Inside the catalog and the picker](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/models/HARNESS_MODELS_INTERNALS.md)
  - [Inside the discovery](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/models/discovery/HARNESS_MODELS_DISCOVERY_INTERNALS.md)
- [Inside the benchmark](../nucleo-core/src/main/java/ai/redouble/nucleo/tools/benchmark/TOOLS_BENCHMARK_INTERNALS.md)
- [Inside the Anthropic provider](../nucleo-provider-anthropic/src/main/java/ai/redouble/nucleo/providers/anthropic/PROVIDERS_ANTHROPIC_INTERNALS.md)
- [Inside the Bedrock provider](../nucleo-provider-bedrock/src/main/java/ai/redouble/nucleo/providers/bedrock/PROVIDERS_BEDROCK_INTERNALS.md)
  - [Inside Claude on Bedrock through the Anthropic SDK](../nucleo-provider-bedrock-anthropic/src/main/java/ai/redouble/nucleo/providers/bedrock/anthropic/PROVIDERS_BEDROCK_ANTHROPIC_INTERNALS.md)
- [Inside the OpenAI provider](../nucleo-provider-openai/src/main/java/ai/redouble/nucleo/providers/openai/PROVIDERS_OPENAI_INTERNALS.md)
- [Inside conversations](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/conversation/HARNESS_CONVERSATION_INTERNALS.md)
  - [Inside compaction](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/conversation/compaction/HARNESS_CONVERSATION_COMPACTION_INTERNALS.md)
- [Inside the model clients](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/llm/HARNESS_LLM_INTERNALS.md)
  - [Inside per-block encoding](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/llm/encode/HARNESS_LLM_ENCODE_INTERNALS.md)
- [Inside decision calls and the decision thinker](../nucleo-core/src/main/java/ai/redouble/nucleo/tools/deciding/TOOLS_DECIDING_INTERNALS.md)
- [Inside the decision wire](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/decision/HARNESS_DECISION_INTERNALS.md)
- [Inside the System One provider](../nucleo-provider-systemone/src/main/java/ai/redouble/nucleo/providers/systemone/PROVIDERS_SYSTEMONE_INTERNALS.md)
- [Inside the job runtime](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/HARNESS_INTERNALS.md)
- [Inside admission](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/admission/HARNESS_ADMISSION_INTERNALS.md)
- [Inside events](../nucleo-core/src/main/java/ai/redouble/nucleo/events/EVENTS_INTERNALS.md)
  - [Inside heartbeat events](../nucleo-core/src/main/java/ai/redouble/nucleo/events/heartbeat/EVENTS_HEARTBEAT_INTERNALS.md)
  - [Inside retry events](../nucleo-core/src/main/java/ai/redouble/nucleo/events/retry/EVENTS_RETRY_INTERNALS.md)
- [Inside observability](../nucleo-core/src/main/java/ai/redouble/nucleo/harness/observability/HARNESS_OBSERVABILITY_INTERNALS.md)
- [Inside settings](../nucleo-core/src/main/java/ai/redouble/nucleo/NUCLEO_INTERNALS.md)
- [Inside credentials](../nucleo-core/src/main/java/ai/redouble/nucleo/secrets/SECRETS_INTERNALS.md)
- [Inside the Spring Boot starter](../nucleo-spring-boot-starter/src/main/java/ai/redouble/nucleo/spring/SPRING_INTERNALS.md)
- [Inside the Quarkus extension](../nucleo-quarkus/src/main/java/ai/redouble/nucleo/quarkus/QUARKUS_INTERNALS.md)
- [Inside the MCP client](../nucleo-core/src/main/java/ai/redouble/nucleo/mcp/MCP_INTERNALS.md)
  - [Inside MCP authorization](../nucleo-core/src/main/java/ai/redouble/nucleo/mcp/auth/MCP_AUTH_INTERNALS.md)
- [Inside the MCP server](../nucleo-core/src/main/java/ai/redouble/nucleo/mcp/server/MCP_SERVER_INTERNALS.md)
- [Inside the chat surface](../nucleo-core/src/main/java/ai/redouble/nucleo/chat/CHAT_INTERNALS.md)
  - [Inside the chat messages](../nucleo-core/src/main/java/ai/redouble/nucleo/chat/messages/CHAT_MESSAGES_INTERNALS.md)
- [Inside literature search](../nucleo-ext-lit/src/main/java/ai/redouble/nucleo/ext/lit/EXT_LIT_INTERNALS.md)
- [Inside the EPO tools](../nucleo-ext-patent/src/main/java/ai/redouble/nucleo/ext/patent/epo/EXT_PATENT_EPO_INTERNALS.md)
- [Inside the USPTO ODP tools](../nucleo-ext-patent/src/main/java/ai/redouble/nucleo/ext/patent/usptoodp/EXT_PATENT_USPTOODP_INTERNALS.md)
- [Inside the demo](../nucleo-demo/src/main/java/ai/redouble/demo/DEMO_INTERNALS.md)
  - [Inside the decision agent](../nucleo-demo-engine/src/main/java/ai/redouble/demo/decide/DEMO_DECIDE_INTERNALS.md)
  - [Inside the Quarkus host](../nucleo-demo-quarkus/src/main/java/ai/redouble/demo/quarkus/DEMO_QUARKUS_INTERNALS.md)

## Appendix

- [Contributing](../CONTRIBUTING.md)
- [Code of conduct](../CODE_OF_CONDUCT.md)
- [Security](../SECURITY.md)
- [Changelog](../CHANGELOG.md)
- [Utilities](../nucleo-core/src/main/java/ai/redouble/nucleo/util/PACKAGE.md)
- [The site generator](../nucleo-docs/src/main/java/ai/redouble/docs/PACKAGE.md)
