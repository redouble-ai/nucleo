# Package: ai.redouble.nucleo.harness.llm

Every model provider has its own SDK, its own request format, its own errors and its own
rate limits. Code written against one of them has to be rewritten for the next, and code
that calls a provider without regard to its limits fails at the worst moment, under load.

This package is the layer between Nucleo and the providers. Above it, thinkers, tools and
your own code speak one way to every model: an `LLMClient` takes a conversation
([Conversations](../conversation/PACKAGE.md)) and returns an `LLMResponse`, through
`singleResponse` or `streamResponse`. Below it, one client per provider turns that
conversation into the provider's own request, reads the provider's answer back, and says
what its errors mean. Before any request leaves, the call's share of the provider's rate
limit has already been reserved.

## One client for every provider

You rarely hold a client yourself. An agent's calls to its model are `LLMCall` jobs, and
each gets its client from the model its binding resolved to
([The catalog, grades and the picker](../models/PACKAGE.md)). A job of your own that calls a
model directly asks its resources for one, `JobResources.getLLMClient(spec)`, and the
client comes wrapped so that every call is recorded, costed and counted against the
workflow's cap.

Which client serves a model is written in the model's catalog entry, as its provider key:
`anthropic-direct`, `anthropic-bedrock`, `bedrock-converse`, `openai` and the others. A
provider becomes available when its module is on the classpath: Nucleo finds every
`ClientProvider` there at startup, and builds a client the first time a model of that
provider is called, so a provider nobody uses never needs its credentials. The provider
pages say what each one serves: [Anthropic](../../../../../../../../../nucleo-provider-anthropic/src/main/java/ai/redouble/nucleo/providers/anthropic/PACKAGE.md),
[Amazon Bedrock](../../../../../../../../../nucleo-provider-bedrock/src/main/java/ai/redouble/nucleo/providers/bedrock/PACKAGE.md),
[OpenAI](../../../../../../../../../nucleo-provider-openai/src/main/java/ai/redouble/nucleo/providers/openai/PACKAGE.md).

Each client does only what differs between providers: how each part of a message is
written in the provider's format ([Per-block encoding](encode/PACKAGE.md)), how the
provider's errors are read, how it authenticates, and how it reports tokens. Everything
the providers share lives once, in `AbstractLLMClient`: the rate-limit lifecycle, the
retries, the limit on the answer, the record of each call. A model called through any
provider behaves the same to the code above.

## Rate limits are respected before a request is sent

Providers publish their limits per minute: so many tokens, so many requests. The usual
client sends the request, catches the "too many requests" error (HTTP 429), sleeps and
tries again. By then the job has already taken a database connection and started a
transaction, and it holds them while it sleeps. Ten jobs refused together sleep together
and retry together, and are refused together again.

Nucleo reserves before it sends. A job declares the model call it is about to make, with
`requireModel` on its `JobRequirements`, and the call is priced at its input tokens plus
the limit on its answer. [Admission](../admission/PACKAGE.md) grants that reservation
together with everything else the job needs, or the job waits holding nothing. When the job
runs, the capacity is already its own.

The account behind the reservation is a `TokenBucketRateLimiter` per model, sized from the
`tpm` and `rpm` of its catalog entry. It refills continuously, and a waiting job is woken at
the moment enough has refilled for it. A single call larger than the whole bucket can never
fit, so it fails at once with the reason instead of waiting forever.

A provider may still slow down below its published limits. When it answers "too many
requests", the bucket refills more slowly for everyone calling that model, and when it
answers "overloaded" (HTTP 529), more slowly still; every run of successful calls speeds it
back up. The slowdown can reach ten times, and settles at the rate the provider is
actually willing to serve.

## When a provider still says no

The SDKs' own retries are switched off, and Nucleo decides what each failure means:

- **Too many requests, overloaded, or a server error** (429, 529, 5xx): the job is run
  again, with jitter and fresh resources, up to its `Job.getUpstreamRetries()` budget. The
  agent above sees none of it unless the budget runs out.
- **The account is out of money**: `QuotaExhaustedException`, at once. Retrying cannot
  help.
- **The answer was cut off at its limit**: the call runs once more with the model's full
  output ceiling. An answer already cut at that ceiling fails, since there is no larger
  limit to give it.
- **The answer does not fit the declared shape**: the model is told what was wrong and
  asked again, up to `LLMCall.MAX_CORRECTIONS` times per call.
- **The provider refused on safety grounds**: `ProviderRefusalException`, carrying the
  provider's category and explanation, so the caller can resubmit the work to another
  model. It is never retried on the model that refused.

A streamed answer is retried only before its first piece has arrived. After that, a retry
would send the reader text they have already seen, so the failure is reported instead.

## Prompt caching on each provider

What caching saves and how a conversation marks what is cached are on the
[Conversations](../conversation/PACKAGE.md) page. The providers differ in how it works:
Anthropic, direct or on Bedrock, caches what is marked, with up to four marks per request;
OpenAI caches the start of every request on its own; Bedrock Converse reports cache reads
and writes in its usage. Each response carries the cache tokens its provider reported, and
the catalog entry prices them.

## Embeddings and decisions

The same layer serves the two other kinds of model. An embeddings model turns text into a
vector for search:

```java
// In a job: the spec comes from the embeddings ModelBinding the harness resolved;
// a client is never constructed directly
EmbeddingsClient client = resources.getEmbeddingsClient(spec);
EmbeddingsResponse response = client.embed("text to embed", EmbeddingPurpose.DOCUMENT);
float[] vector = response.getVector();
```

`EmbeddingPurpose` says whether the text is stored (`DOCUMENT`) or searched for (`QUERY`),
which some providers embed differently. Every client returns vectors of one fixed width,
normalized, whatever the provider produces natively, so the columns that store them stay
one size when the provider changes. Each call is recorded, costed and capped like a chat
call.

A decision model answers typed questions about a state with a probability for each option,
and writes no text ([What a decision model is](../../tools/deciding/PACKAGE.md)):

```java
// In a job: the spec comes from the decision ModelBinding the harness resolved
DecisionClient client = resources.getDecisionClient(spec);
DecisionResponse response = client.decide(new DecisionRequest(ticket, Map.of(
        "department", Choice.of("Which team should handle this?", "billing", "technical", "sales"),
        "is_urgent", Noul.of("Does this convey urgency?"))));
String team = response.choice("department").choice();
double urgent = response.noul("is_urgent").probability();
```

Its wire and vocabulary are on [The decision wire](../decision/PACKAGE.md).

## Credentials

No client reads an environment variable of its own. Each provider names its credential by
an id, and the value comes from the deployment's secret store
([Credentials](../../secrets/PACKAGE.md)), so rotating a key touches neither code nor
configuration:

| Constant | Id | Purpose |
|---|---|---|
| `AnthropicSDKClient.SECRET_ID` | `anthropic-api-key` | the Anthropic direct API key |
| `OpenAIProvider.SECRET_ID` | `openai-api-key` | the OpenAI API key; for Azure the host as well |
| `BedrockClients.SECRET_ID` | `aws-access-key-id` | the AWS credential pair (user = access key id, secret = secret access key) for a deployment with its own store; in the environment, AWS's own `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` are read by the chain instead |
| `BedrockClients.REGION_ID` | `aws-region` | the Bedrock region for a deployment with its own store; in the environment, AWS's own `AWS_REGION` |

When the store holds no AWS record, Bedrock falls back to AWS's own default chain, so a
deployment running under an AWS role provisions no key anywhere. Nothing is defaulted: a
region the deployment did not choose fails on the first call.

The settings of this layer, such as the Mantle project ids and the catalog's picker and
backend, are fields of `ModelSettings`, assigned by the deployment's configurator
([Settings and the configurator](../../PACKAGE.md)).

## How it works inside

The token bucket and the throttle, the retry classification, the output budget, the client
templates, every provider key and its classes, the two Bedrock endpoints for Claude and
their data-retention binding, and the embeddings and decision templates are in
[Inside the model clients](HARNESS_LLM_INTERNALS.md), for those working on the runtime
itself.
