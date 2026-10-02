# ai.redouble.nucleo.providers.openai

OpenAI's API is also the API many other services speak: self-hosted model servers,
aggregators that route to many vendors, Microsoft's Azure. This library,
`nucleo-provider-openai`, calls all of them. It connects to OpenAI itself, to any endpoint
that speaks OpenAI's API, to Azure AI Foundry, and to Azure OpenAI's embedding models.

## What it connects to, and the credential each needs

| Provider key | Connects to | Credential |
|---|---|---|
| `openai` | OpenAI's chat models, on its Responses API | `OPENAI_API_KEY` |
| `openai-chat-completions` | the same models on OpenAI's older Chat Completions API, for an entry that must be called there | `OPENAI_API_KEY` |
| `openai-embeddings` | OpenAI's embedding models | `OPENAI_API_KEY` |
| `openai-compatible` | any endpoint that speaks Chat Completions | `OPENAI_COMPATIBLE_API_KEY` and `OPENAI_COMPATIBLE_API_KEY_HOST` |
| `openai-compatible-responses` | such an endpoint that speaks the Responses API | the same |
| `openai-compatible-embeddings` | the embedding models of such an endpoint | the same |
| `azure-foundry-openai` | the chat models deployed on an Azure AI Foundry resource | `AZURE_FOUNDRY_API_KEY` and `AZURE_FOUNDRY_API_KEY_HOST` |
| `azure-openai-embeddings` | embedding deployments on an Azure OpenAI resource | `OPENAI_API_KEY` and `OPENAI_API_KEY_HOST` |

In a secret store of the deployment's own, the three credentials are `openai-api-key`,
`openai-compatible-api-key` and `azure-foundry-api-key`, each with its key and, where the
table names one, its host. [Set up and run the demo](../../../../../../../../../AGENTS.md)
says how a credential reaches the runtime.

**OpenAI's two APIs.** OpenAI serves its models on two APIs. The Responses API is the one
where a reasoning model can reason and call tools in the same turn, so `openai` uses it;
`openai-chat-completions` exists for an entry that must be called on the older one.

**An OpenAI-compatible endpoint.** The host is the API root the paths are appended to. The
Hugging Face router, `https://router.huggingface.co/v1` with a Hugging Face access token
that has the "Inference Providers" permission, reaches every model the router serves. A
local server works the same way, `http://localhost:11434/v1` for Ollama. A root without a
scheme is refused with the accepted forms named. The library ships no catalog entries for
these providers, because the models behind such an endpoint are the deployment's to know:
an endpoint that lists its models, as the router does, is filled in by the discovery, and
one that does not needs its entries written in the deployment's `models.json`. A deployment
with several such endpoints subclasses `OpenAICompatibleProvider` once per endpoint, each
with its own provider key and credential id.

**Azure AI Foundry.** The key is the resource key and the host is the resource's hostname,
such as `my-resource.services.ai.azure.com`. The provider serves the models deployed on the
resource under OpenAI's API: the Azure OpenAI models and the other vendors Azure sells in
that form. The discovery lists the resource's deployments by the names the people who
created them gave them; the library ships no entries, since those names are the
deployment's. Claude on Foundry is served under a different API and is not this provider's.
Embedding deployments on an Azure OpenAI resource are not listed at all: their entries are
written in the deployment's `models.json`, and the discovery keeps them as written.

## What it serves

Chat with tool calls in OpenAI's own format, pictures, streaming through OpenAI's own
client, and the reasoning effort of the request's depth on reasoning models; and
embeddings. The library ships a small
catalog of OpenAI entries with the public facts and OpenAI's tier-1 limits, used when the
deployment has no `models.json` of its own. OpenAI lists no rate limits; each response
carries the account's limits in its `x-ratelimit-*` headers, and the discovery's one call per
model writes them into the catalog.

Whatever an endpoint answers is checked before your code sees it. An answer that is not an
answer, a body that is not JSON, a reply with no message, a tool call with no name, fails
by name and is never passed on as empty. A rate limit, an exhausted quota, a server error
and an unreachable endpoint each reach the runtime as what they are, so it waits, retries or
stops accordingly. Neither the key nor the conversation appears in any failure's message.

## How it works inside

The encoders, the two dialects and their transports, the Azure Foundry wiring, the rules a
third party's answers are held to and the listings are in
[Inside the OpenAI provider](PROVIDERS_OPENAI_INTERNALS.md), for those working on the
runtime itself.
