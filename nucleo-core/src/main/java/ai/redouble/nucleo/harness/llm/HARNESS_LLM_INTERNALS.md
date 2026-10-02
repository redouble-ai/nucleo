# Inside the model clients

This page is for people working on the runtime itself: the token bucket and the adaptive
throttle, the retry classification, the output budget symmetry, the client templates, the
provider hierarchy, the Bedrock surfaces and the embeddings and decision templates. The
guide is [Model clients and rate limits](PACKAGE.md).

---

## Pre-emptive rate limiting

Tokens are debited BEFORE the API call, not after failure, and together with everything else the job needs.

```
Job declares its need                            ← requireModel(...) prices input + max_tokens
    ↓
Admission grants the whole demand                ← tokens + HTTP permit + ... at once, or park holding nothing
    ↓
API call executes                                ← Guaranteed to have capacity
    ↓
onSuccess / onRateLimitError                     ← The upstream's verdict feeds the throttle
```

**Token Bucket Implementation** (`TokenBucketRateLimiter.java`):
- Dual buckets: Requests Per Minute (RPM) and Tokens Per Minute (TPM)
- Clocked, not ticked: the level at any instant is the stored level plus what the rate has refilled since it was last read, capped at the budget. There is no refill thread. `earliestFit` tells the admission evaluator the exact instant a shortfall closes, and the evaluator parks until then.
- Never blocks: it is an admission account, and `ai.redouble.nucleo.harness.admission.Admission` does the waiting.
- Implements `ai.redouble.nucleo.harness.admission.RateLimiter<Integer>` - the account contract lives in Nucleo, so the token bucket is granted, reserved for the head of the admission queue and rolled back alongside memory, elastic windows, and counting gates. It declares `replenishment() == TIME`: tokens are spent against the per-minute budget when the request goes upstream, so release leaves them to refill with time rather than handing them back. `give` is reached only on rollback and grant compensation, when the call never happened.
- Refuses what can never fit: a reservation above the configured TPM, or more requests than the RPM, throws an `UncorrectableRuntimeLLMException` from `fits`, so a job that prices a prompt larger than the bucket fails at once with the reason instead of parking forever.

**Adaptive Throttle Coefficient**:

When an upstream slow-down signal arrives, the system doesn't just retry - it stretches the refill window proportionally, weighted by the signal (`recordBackpressure(increments)`, with `record429()` the weight-1 entry):

```java
// On a 429 (recordBackpressure(1) via record429):
throttleCoefficient += 0.5;  // Refill becomes 1.5x slower

// On a 529 (recordBackpressure(3)):
throttleCoefficient += 1.5;  // The whole provider fleet is saturated - yield hard now

// On success (recordSuccess):
throttleCoefficient -= 0.2;  // every third success
throttleCoefficient -= 0.5;  // every success, after five minutes without a backpressure signal
```

The weight comes from the signal itself (`UpstreamRetryException.backpressureIncrements()`); the controller does not distinguish 429 subtypes (acceleration / capacity / per-second burst / vendor-specific). Any upstream "slow down" signal feeds the same loop, and the coefficient converges on whatever rate the provider is actually willing to serve without needing vendor-specific header parsing. The coefficient ranges 0.0-9.0 (up to 10x slower refill).

**Result**: Jobs block waiting for capacity, then execute. A 429 that does slip through is caught by the dispatcher, re-queued with jitter, and fed back into the throttle so the next attempt has a proportionally wider window. Thinkers never see a 429 unless the job's transparent retry budget (`Job.getUpstreamRetries()`) is exhausted. The streaming template classifies the same way for any failure before the first chunk. Once a chunk has been delivered, no retry signal may escape - a transparent re-run would re-deliver text the consumer already rendered - so a failure that classifies as a capacity signal feeds the throttle its backpressure weight and then fails as an uncorrectable naming the mid-stream position, while an unrecognized failure propagates as the provider threw it.

**Output budget symmetry**: The local reservation and the upstream `max_tokens` field resolve through a single source: `ConversationContext.outputReserve(spec)`, the seat's declared output plus the reasoning headroom the call books, clamped at the model's `maxOutputTokens`. The output comes from three declaration layers and no default - the last outgoing message's `requestedOutputTokens` (a per-call override), else the conversation's `OutputDeclaration` (the thinker's rung or count, stamped by `ConversationService`), else the wired binding's declaration (a job that attached its priced binding declared the output in its model requirement); nothing declaring anywhere is uncorrectable, never a number the framework invents. The headroom is `ThinkingMode.reasoningReserved(model, depth)` times `ModelSpec.getThinkingBudget(depth)`: the Anthropic modes above IMMEDIATE, and a reasoning-effort model at every depth, because it reasons inside the same completion ceiling. `ModelBinding.price()` and every SDK client's `resolveWireMaxTokens` consume that one formula, so local bucket accounting and what the provider pre-debits cannot drift out of sync. `LLMCall` takes no model of its own: it reads the conversation's, so the reservation, the wire ceiling and the truncation ceiling are all one spec. Each client maps its provider's raw stop/finish reason into the normalized `LLMStopReason` on the response and records the requested ceiling (`LLMResponse.requestedMaxTokens`); the abstract client's `singleResponse`/`streamResponse` templates then make the truncation decision in one place (`AbstractLLMClient.throwIfTruncated`) via `LLMResponse.wasTruncated()`. `wasTruncated()` keys off `LLMStopReason.MAX_TOKENS`, with a backstop of output reaching the requested ceiling for backends (e.g. Anthropic-over-Bedrock) that do not surface a typed stop reason. The same two templates gate a refusal first (`AbstractLLMClient.throwIfRefused`): a stop reason of `CONTENT_FILTERED` (Anthropic's `refusal`, a guardrail, a content filter) comes with an empty body, and left alone that body reaches the parser as "empty response" and a thinker spends its whole correction budget re-asking a question the provider has already declined; instead the call fails as `ProviderRefusalException` (an `UncorrectableRuntimeLLMException`) carrying the model that refused, the provider's category word (`LLMResponse.getRefusalCategory()`, Anthropic's `stop_details.category`) and its explanation (`LLMResponse.getRefusal()`), on the single and streaming paths alike. Never retried on that model, since a safety classifier is not re-rolled; the type exists so a caller that catches it can read the category and resubmit the same work on another model. The escalation happens there too: the client bumps the SENT message's budget to the model ceiling and throws `OutputTruncationRetryException`; the dispatcher re-runs the job one time, and a job that retains its conversation across attempts (a thinker's `LLMCall`, a one-call job that builds its conversation once) reserves and sends the escalated budget with nothing to opt into, while a job that rebuilds its conversation per call re-runs at its declared budget, which the dispatcher then names as the reason on the second truncation. A call already issued at the ceiling has no larger budget to grow into, so it raises `UncorrectableRuntimeLLMException` rather than a retry that would repeat the same truncation.

**Per-response facts are stamped by the templates**: after each call, the single and streaming templates set the response's model, provider key, requested ceiling, and end time - no client stamps these individually, so none can forget. The model is the catalog id of the spec we called, never the name the provider echoes back: the echo collapses variants (every Haiku endpoint echoes the same wire name; Converse echoes nothing), while the catalog id is identical on successes and failures, so recorded calls attribute cleanly per spec and carry a real latency.

### SDK Retry Disabled - Framework Handles Retries

All LLM SDK clients are configured with zero retries (`maxRetries(0)` for Anthropic, `maxAttempts(1)` for AWS). This prevents SDK-internal retry/backoff from conflicting with the framework's job timeout. When an API call fails:

- **429 (rate limit)**: Detected by `is429Error()`, thrown as `RateLimitRetryException`. The dispatcher retries transparently with jittered backoff and fresh resources/timeout per attempt.
- **529 (overloaded)**: Detected by `isOverloadError()` (checked before the generic 5xx branch, which a 529 also matches), thrown as `OverloadRetryException`. Same transparent retry, but the signal is a capacity statement rather than a fault or a budget violation, so it waits in a wider jitter window (overload episodes last minutes while the refusals fast-fail in seconds) and bumps the model's fleet-wide throttle harder than a 429 - every concurrent job is receiving the same signal, so the fleet yields hard immediately and success-driven recovery walks it back. Only the Anthropic dialects have a dedicated overload status; other providers answer false and their capacity pressure rides whichever signal they actually emit.
- **Out of money (quota/credits exhausted)**: Detected by `isQuotaError()`, thrown as `QuotaExhaustedException` (uncorrectable, naming the account). OpenAI signals it as a 429 (`insufficient_quota`), Anthropic as a 400 ("credit balance is too low"); either way it never recovers by retrying, so it is checked before `is429Error()` and surfaces as a clear failure instead of feeding the retry loop. Providers whose only quota errors are capacity/throttle limits (Bedrock service quotas, Azure deployment limits) do not override `isQuotaError()` - those stay on the retryable 429 path.
- **5xx (server error)**: Detected by `isServerError()`, thrown as `TransientErrorRetryException`. Same dispatcher retry pattern, but a fault feeds the throttle nothing - cutting the fleet's admission rate over a stray 500 would be an overreaction.
- **Truncated output** (normalized `LLMStopReason.MAX_TOKENS`, or output reaching the requested ceiling as a backstop): the abstract client template bumps the sent message's output budget to the model ceiling and throws `OutputTruncationRetryException` centrally for every client and both the single and streaming paths; the dispatcher re-runs once, and a caller that retained its conversation reserves and sends the escalated budget.
- **Malformed or invalid response**: `LLMCall` appends a correction message to the conversation (the error explanation, via `JsonParseException` / `ResponseValidationException`) and throws `ResponseCorrectionRetryException`; the dispatcher re-runs so the model can fix its output. Bounded by `LLMCall.MAX_CORRECTIONS` per call; on exhaustion a parse failure surfaces as `JsonParseException` while a validation failure logs a structured WARN and the response is returned as-is (hard validation enforcement belongs to contract owners, e.g. the fan-out doer's cell collection).
- **Schema-echo answers**: a format-literal model (the Nova family, observed on real traffic) answers a structured request in the schema notation itself - values filled into `@fields` under `@type` - instead of as a bare instance. `NucleoJsonSerializer.parseLLMResponse` normalizes that shape before mapping: every `@fields` object hoists into its parent recursively and `@`-prefixed notation keys drop, recovering exactly the instance the answer encodes. Left unnormalized it maps to an all-null POJO and reads as a silent decline.

The three upstream signals (429, 529, plain 5xx) share the sealed parent `UpstreamRetryException`: the dispatcher has ONE transparent-retry loop catching the parent, and each signal carries its own pacing window and fleet-throttle weight as data (`baseMinJitterMs`/`baseMaxJitterMs`/`backpressureIncrements`, applied by `TokenBucketRateLimiter.recordBackpressure`). These are RuntimeExceptions that bypass the job's normal retry policy and share one attempt counter; the thinker never sees them unless all retry attempts are exhausted. `LLMReadableCheckedException.unwrap` rethrows the parent from any cause chain, so a broad catch cannot accidentally convert a retryable signal into a terminal failure.

---

## Provider Abstraction

| Class | Provider | Native Tools | Notable Features |
|-------|----------|:---:|------------------|
| `AnthropicSDKClient` | Anthropic Direct | Yes | Streaming, prompt caching (4 breakpoints), extended thinking |
| `AnthropicBedrockSDKClient` | AWS Bedrock (Claude) | Yes | Anthropic SDK with Bedrock backend, prompt caching |
| `BedrockConverseClient` | AWS Bedrock (all models) | No | Converse API, supports Nova, Llama, Mistral, etc. |
| `OpenAISDKClient` | OpenAI | Yes | OpenAI's own client; streaming, native tools, the answer bound to a JSON object, automatic cache-read tracking |
| `OpenAICompatibleClient` | Any OpenAI-dialect endpoint | Yes | The same dialect over the framework's HTTP client, lenient about what comes back |

A concrete client (`AbstractLLMClient<B>`) carries only its provider difference: how it encodes each content block, how it classifies the provider's errors, its auth/transport bootstrap, and its token-accounting shape. Cross-provider machinery lives in the abstract template - the rate-limit lifecycle, the request/response log envelope and caller attribution, `resolveWireMaxTokens` (the single source for both the wire `max_tokens` and the response truncation ceiling), and `setSuccessful`/provider stamping after `doSingleResponse`.

The output budget that feeds all of this is capped at `ModelSpec.getMaxOutputTokens()` inside `ConversationContext.outputReserve(spec)`, not in the clients. A budget above the model's ceiling is not a budget the provider will honour - Bedrock answers a 400 naming the ceiling - and the local reservation would meter tokens the call could never emit. Capping at the single source keeps the wire `max_tokens` and the `JobRequirements` reservation equal by construction, where a per-client clamp would have to be repeated in every provider and silently skipped by the next one; the cap is what lets a seat whose declaration exceeds a small model's ceiling (a STANDARD rung on Nova Micro) run at that ceiling instead of failing every call. The same formula adds the reasoning headroom: Anthropic counts thinking toward `max_tokens`, and the reasoning generation on OpenAI and gpt-oss reasons inside `max_completion_tokens` / `maxTokens`, so those clients send `reasoning_effort` from the call's depth and the entry's thinking budget rides above the declared answer on every provider alike.

Truncation retry follows from the cap: `throwIfTruncated` raises `OutputTruncationRetryException` only while a larger budget exists. A call already issued at the model ceiling has nowhere to grow, so it fails with `UncorrectableRuntimeLLMException` instead of spending a second identical upstream call to reach the same truncation.

Channel assignment - what is system content and what is a turn - is likewise decided once, in `ConversationContext.prepareMessagesForLLM`, which returns a `PreparedConversation`: the system content (the main objective, the conversation's only system content) and the turns (`TurnRole.USER` / `ASSISTANT`, an enum with no system member), separately. Clients render that shape into their dialect and make no routing decisions of their own. The full contract, including the cache flag and the registry's trailing user turn, is in [Inside conversations](../conversation/HARNESS_CONVERSATION_INTERNALS.md).

### Per-block Encoding

A content block becomes a provider-native block through a `block.class -> BlockEncoder.class` map on the client; the encoding logic lives entirely in the `BlockEncoder` hierarchy ([encode/PACKAGE.md](encode/PACKAGE.md); the provider-native subclasses sit in the provider modules), never in the client. Base encoders (one per block type) render text through an injected `TextWrapper`; a provider that renders a block natively subclasses the logical parent (e.g. `AnthropicImageBlockEncoder extends ImageBlockEncoder`). Because every block type resolves to an encoder (the base default at worst), no client can silently drop a block - the failure mode is a faithful text fallback, not a vanished block.

### Native Tool Calling

Providers declare native tool support via `ContentFormatter.supportsNativeToolCalling()`. When supported:

- `ToolDefinitionBlock`s in the conversation are sent via the provider's native tool API (Anthropic's `tools`, the Chat Completions `tools` parameter)
- The response schema excludes `tool_calls` - the model uses native `ToolUseBlock`s with provider-assigned IDs
- Tool results are sent back as `ToolResultBlock`s linked by ID
- Freeform text alongside tool calls is captured as reasoning

When not supported, tools are described as text in the prompt and the model returns tool calls in the JSON response. The thinker receives a `ThinkingResponse` either way - the encoding is transparent.

### Client construction: the ClientProvider hierarchy

Clients are never constructed directly. `ClientProviders.llmClient(spec)` / `ClientProviders.embeddingsClient(spec)` resolve the spec's `providerKey` to a `ClientProvider` and return the bare, app-shared client per spec id (a single guarded cast enforces the LLM vs embeddings family); `ClientProviders.shutdownClients()` closes them together at application shutdown. The provider hierarchy mirrors the client hierarchy:

| Provider key | `ClientProvider` | Client |
|---|---|---|
| `anthropic-direct` | `AnthropicDirectProvider` | `AnthropicSDKClient` |
| `anthropic-bedrock` | `AnthropicBedrockProvider` (module `nucleo-provider-bedrock-anthropic`) | `AnthropicBedrockSDKClient` |
| `anthropic-bedrock-mantle` | `AnthropicBedrockMantleProvider` (module `nucleo-provider-bedrock-anthropic`) | `AnthropicBedrockMantleSDKClient` |
| `openai` | `OpenAIProvider` | `OpenAISDKClient` (Responses) |
| `openai-chat-completions` | `OpenAIChatCompletionsProvider` | `OpenAISDKClient` (Chat Completions) |
| `openai-embeddings` | `OpenAIEmbeddingsProvider` | `OpenAISDKEmbeddingsClient` |
| `openai-compatible` | `OpenAICompatibleProvider` | `OpenAICompatibleClient` (Chat Completions) |
| `openai-compatible-responses` | `OpenAICompatibleResponsesProvider` | `OpenAICompatibleClient` (Responses) |
| `openai-compatible-embeddings` | `OpenAICompatibleEmbeddingsProvider` | `OpenAICompatibleEmbeddingsClient` |
| `azure-foundry-openai` | `AzureFoundryOpenAIProvider` | `AzureFoundryOpenAIClient` |
| `azure-openai-embeddings` | `AzureOpenAIEmbeddingsProvider` | `AzureOpenAIEmbeddingsClient` |
| `bedrock-converse` | `BedrockConverseProvider` | `BedrockConverseClient` |
| `bedrock-cohere-embeddings` | `BedrockCohereEmbeddingsProvider` | `BedrockCohereEmbeddingsClient` |
| `systemone-decision` | `SystemOneProvider` (module `nucleo-provider-systemone`) | `SystemOneClient`, the decision family, behind a key |
| `systemone-local-decision` | `LocalSystemOneProvider` (module `nucleo-provider-systemone`) | `SystemOneClient`, the decision family, on this machine |

`ClientProvider<E extends Client>` is generic over the single client it builds; `AnthropicProvider<E extends AnthropicSDKClient>` carries the shared Anthropic construction (including OkHttp pool tuning) for its direct and Bedrock subclasses. Providers are discovered by the `ClientProviders` classpath scan and instantiated eagerly (they read no secrets); the secret-reading client is built lazily by `createClient`, so a deployment that never references a provider never needs its credentials. As it loads a provider the scan puts the shapes of the credentials the provider declares it reads (`credentialShapes()`: which parts each has and the environment variable each part comes from, an API key alone by default, AWS's own two variables for the Bedrock pair) on record in `CredentialShapes`, so every store reads and describes a provider's credential by the provider's own declaration. A new vendor is a drop-in `public` `ClientProvider` plus its client - there is no `inferProvider` and no global client-class setting. A provider answers two questions about a wire id without a call: `serves` (its client speaks that model's request shape; true by default) and `claims` (it is written for that model's family, as the Anthropic providers are for Claude; false by default). Its `addressing` names the endpoint family its wire ids are spelled for (its platform by default; Mantle's is `bedrock-mantle`). The catalog loader asks all three when an entry names a provider this classpath does not carry, to link the entry to a provider here that addresses its model the same way (`ProviderLinks`, in [../models/PACKAGE.md](../models/PACKAGE.md)).

Beyond `configured()`/`describeCredential()`, a provider states its `connectionFacts()`: non-secret, deployment-chosen facts about where its calls go (the credential's `host` by default, the resolved AWS region for the Bedrock surfaces), resolved locally the way `createClient` would resolve them. Status surfaces show them next to the configured flag, because a deployment can hold a credential and still point somewhere wrong, and the call failure that follows typically names neither; a fact whose resolution fails carries the failure's message as its value.

### Bedrock has two endpoints: bedrock-runtime and Mantle

AWS serves Claude on two distinct surfaces, and they are not interchangeable. `anthropic-bedrock` is the legacy `bedrock-runtime` path (InvokeModel, `BedrockBackend`); `anthropic-bedrock-mantle` is the Mantle path (`bedrock-mantle.{region}.api.aws/anthropic/v1/messages`, the native Anthropic Messages API, `BedrockMantleBackend`). Same AWS credentials, same region, same pricing - so `AnthropicBedrockMantleSDKClient` extends the legacy client and swaps only the backend, inheriting throttle classification, model resolution and rate-limit header extraction.

Four things differ, and each is why a separate spec family exists rather than a swapped backend:

| | `anthropic-bedrock` | `anthropic-bedrock-mantle` |
|---|---|---|
| Model id | `us.` / `global.` inference profile | bare `anthropic.claude-opus-5` |
| Catalog | every Claude release we use | newer releases only (Sonnet 4.6 and older: "does not exist") |
| Quota | one combined cross-region TPM | separate pool, published per direction (input TPM / output TPM) |
| Data retention | control-plane account store | Mantle account store, set independently |

Each surface rejects the other's id form, so the id lives on the spec. The `-mantle` specs carry the Mantle input TPM as their `tpm` (our workloads are input-dominated) and omit `rpm`, which is unpublished for that endpoint; `RateLimiterFactory` derives `tpm / 1000`. Because `tpm` is one number and Mantle enforces two, the output ceiling is not modelled - a heavily output-bound workload can hit it without the local bucket noticing.

The Anthropic prompt cache is shared across both surfaces: a prefix written through one is read by the other. Running both in parallel costs no extra cache writes, and a cutover does not start cold.

### Data retention: workspace binding on the Mantle surface

Bedrock resolves a request's data retention mode as project -> account -> model default, first non-inherit wins, and a Mantle request names its project with the `anthropic-workspace-id` header. `AnthropicBedrockMantleSDKClient` binds every request in `buildMessageCreateParams` (both single-shot and streaming flow through it), from three inputs:

- `ModelSpec.requiresLax` - catalog fact: this model is served only under `provider_data_share` (its `allowed_modes` contains nothing else; Fable 5 today). Changes in both directions - new sharing-required models arrive with it set, ZDR eligibility granted by the provider clears it. Ground truth is the model's `allowed_modes` in the account's Mantle catalog.
- `ModelSettings.mantleLaxProject` / `ModelSettings.mantleStrictProject` - deployment facts: the account's two project ids, `provider_data_share` and `none` respectively. Null where not provisioned.
- the application's `ComplianceEnvelope` - sealed into the app's `JobDispatcher` by the host through `JobDispatcher.sealComplianceEnvelope` before `start()`, permanent for the process lifetime; a dispatcher started without one seals the refusing `DefaultComplianceEnvelope`. The default refuses every `requiresLax` spec: no application shares data by omission. One compliance domain per dispatcher, which is one per application - nothing finer, because the MessageBus and shared stores make intra-process data boundaries fiction.

A `requiresLax` spec routes to the LAX project, and only when the sealed envelope permits it - the resolution gate never resolves a non-compliant spec (an unpinned seat is served from the best rung the envelope permits instead, and a non-compliant pin is refused long before a request exists), and the Mantle client re-checks the envelope as the LAST LINE before anything leaves the process (`UncorrectableRuntimeLLMException` naming the envelope). Everything else routes to the STRICT project, which pins ordinary traffic to zero retention independently of the account-level setting; a null project id sends no header and defers to the account. A `requiresLax` model deliberately has no `-bedrock` twin: the legacy surface has no workspace binding, so such a spec could never be called there (`BedrockMantleWiringTest` pins this).

`APIDialect.BEDROCK_MANTLE` keeps `providerKey` as `BEDROCK`, so billing multipliers and the recorded provider value are untouched; the two surfaces are told apart by the recorded `-mantle` spec id.

### The endpoint is a per-model choice

The endpoint is a property of the spec, not a global switch. One logical model served on several endpoints is several catalog entries with distinct ids sharing one `identity`:

- `claude-opus-5-direct` (`providerKey: anthropic-direct`, wire id `claude-opus-5`)
- `claude-opus-5-bedrock` (`providerKey: anthropic-bedrock`, wire id `us.anthropic.claude-opus-5`)
- `claude-opus-5-mantle` (`providerKey: anthropic-bedrock-mantle`, wire id `anthropic.claude-opus-5`)

A deployment's picker pins whichever variant it wants per grade.

### Bedrock Configuration

Bedrock takes an access key id, a secret access key and a region, and the runtime invents no way of providing them. `BedrockClients` consults the deployment's secret store first and otherwise hands the SDK AWS's own default chains, which read what every AWS user already has:

| Source | Credentials | Region |
|---|---|---|
| the deployment's secret store | the record under `BedrockClients.SECRET_ID` (`aws-access-key-id`): user = access key id, secret = secret access key, both halves or it is no record | the record under `BedrockClients.REGION_ID` (`aws-region`) |
| the AWS default chains, when the store has no record | `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` in the environment, a profile, or the role the process runs under (ECS task role, EC2 instance profile, EKS service account) | `AWS_REGION` in the environment, a profile, or instance metadata |

Nothing is defaulted: a region the deployment did not choose is a wrong region, and a wrong region fails on the first call with an access error that says nothing about regions. A deployment on a role provisions no key anywhere: with no record in its store the chain resolves the role, so a deployment inside a customer's regulated account holds no long-lived key at all. A shared-config SSO profile is not resolvable without `software.amazon.awssdk:sso` on the classpath, and a `role_arn`/`source_profile` profile without `sts`; neither module is present, so the chain fails with that message rather than falling back. `BedrockClients.credentialsProvider()` is the single decision point, used by the runtime client and by both Anthropic SDK backends, so chat and embeddings cannot end up authenticating differently. Credentials reach the SDK as a provider, not a fixed pair, because role credentials expire and the SDK must be able to fetch the next ones.

### Model Resolution

`AnthropicModelResolver.resolve(ModelSpec)` returns `Model.of(spec.getWireModelId())`. Each spec already carries its endpoint-specific wire id (direct `claude-opus-5`, Bedrock `us.anthropic.claude-opus-5`, Mantle `anthropic.claude-opus-5`), so there is no tier remapping; a non-Anthropic spec throws.

### Bedrock Converse API (Non-Anthropic Models)

Non-Claude Bedrock models (Nova, Llama, Mistral, Titan) use the `bedrock-converse` provider, which builds a `BedrockConverseClient` from the spec's wire id (`amazon.nova-micro-v1:0`, `amazon.nova-premier-v1:0`, etc.).

Both Bedrock-backed clients (`BedrockConverseClient`, `BedrockCohereEmbeddingsClient`) obtain their `BedrockRuntimeClient` from `BedrockClients.newRuntimeClient()`: credentials per `BedrockClients.credentialsProvider()`, the region per `BedrockClients.region()`, SDK retries disabled (the framework's dispatcher owns retry/backoff), and the audit interceptor registered. `BedrockConverseClient` and `OpenAISDKClient` build their transport on first use rather than at construction - reading a credential is what a request needs, not what an instance needs - so their request builders (`buildConverseRequest`, `buildRequestJson`) are exercisable without secrets, which is what the request-shape tests do.

### Prompt Caching

Cache-cost multipliers are per-provider (see `ModelSpec.getEffectiveCacheReadMultiplier()` / `getEffectiveCacheWriteMultiplier()`); a deployment's usage recorder applies them when it computes billable input tokens.

**Anthropic / Bedrock-Anthropic** (`AnthropicSDKClient`, `AnthropicBedrockSDKClient`) - explicit breakpoint model. Write costs 125% of normal input tokens, reads cost 10%. Up to 4 breakpoints per request. One breakpoint on the last system block covers the whole stable prefix - tool definitions, skills, and the main objective - and is taken when the conversation asks for it (`cacheMainObjective`, default true); the rest go to turns where `msg.cacheEnabled()` is set, in order. Both the count and the apply key on the same rendered system blocks, so they cannot disagree. Anthropic reports usage as three additive counters (`input_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`); the grand total is their sum.

```java
context.setCacheMainObjective(true);  // Cache system prefix (default: true)
message.setCache(true);               // Cache a specific message block
```

**OpenAI** (`OpenAISDKClient`) - automatic caching, no breakpoints. Reads are billed at ~50% (varies by model family); no write overhead. OpenAI reports `prompt_tokens` as the grand total with `prompt_tokens_details.cached_tokens` as a subset.

**Bedrock Converse** (`BedrockConverseClient`, for Nova/Llama/Titan/Mistral) - same additive shape as Anthropic, different AWS field names (`cacheWriteInputTokens` / `cacheReadInputTokens` on `TokenUsage`). The shipped catalog sets both multipliers to 1.0 for this provider.

Cache reads and writes reach the observers: `DefaultMeterCustomizer` counts them as `nucleo.llm.cache_read_tokens` / `nucleo.llm.cache_creation_tokens`, and `DefaultSpanCustomizer` sets `llm.cache_read_tokens` / `llm.cache_creation_tokens` on the call's span.

---

## Embeddings

`embed` is the call; `calculateEmbedding` is the same call returning the vector alone. The `EmbeddingsResponse` is an `LLMResponse` with no conversation: the catalog id the call was issued against, the input tokens the provider billed (Bedrock's `X-Amzn-Bedrock-Input-Token-Count` header, OpenAI's `usage.prompt_tokens`), the latency and the verdict, and no message in or out. A job's `JobResources.getEmbeddingsClient` hands out the client wrapped in `ObservableEmbeddingsClient`, which records every call, successful or failed, on the `JobContext` next to the chat calls, so the terminal event carries it to the cost ledger, the meters, the spans and the platform's usage tables, and embeddings spend is counted and capped like any other model call. `AbstractEmbeddingsClient.doCalculateEmbedding` returns a `RawEmbedding`, the native vector and the billed count, null where a provider reports none.

`embed` takes an `EmbeddingPurpose` (`DOCUMENT` for a stored corpus item, `QUERY` for a live search probe). Providers that embed corpus and probes differently (e.g. Cohere `input_type`) use it; providers that do not (OpenAI, Titan) ignore it. The split is a property of the call site, so it travels through the signature.

Every client returns an L2-normalized vector of a single fixed width. `AbstractEmbeddingsClient` enforces this by running each raw provider vector through `DimensionAdapter.coerce`, which folds (truncate + renormalize, valid for Matryoshka models) when native is larger, zero-pads when native is smaller, and normalizes otherwise. The target width is the spec's `embedding_dimensions` when the catalog entry pins one, else the canonical `EmbeddingsClient.DIMENSIONS`. A pinned spec lets a native-width endpoint skip the lossy expand/fold round-trip entirely: the vector is normalized in place. This fixed-width contract is what lets the database vector columns stay one size across provider switches. Same rate limiting infrastructure as the LLM clients.

`BedrockCohereEmbeddingsClient` (`bedrock-cohere-embeddings`) serves Cohere Embed on Bedrock InvokeModel. It maps `EmbeddingPurpose` onto Cohere's `input_type` (`search_document` / `search_query`), requires its spec to pin `embedding_dimensions` (fails loud at construction otherwise - producing native-width vectors is the point of the endpoint), always sends `output_dimension` and `truncate: "NONE"` (deterministic error on overflow instead of silent truncation), and verifies the returned vector length against the requested dimension before handing it to the coercion template.

---

## Decisions

The third client family. A decision model answers typed questions about a state with a
probability over the options the caller declared, and generates nothing: no text, no
conversation, no tools. The shape is TypeSafe's Jev's and its open replicas' (Kev,
Nimble, OpenJev), which all speak one wire, so the vocabulary and the codec live in the
runtime ([../decision/PACKAGE.md](../decision/PACKAGE.md)) and a provider carries only its
endpoint.

`DecisionClient.decide` is the call. `AbstractDecisionClient` is the template every
provider's client runs: the one wire call inside the rate-limit feedback (skipped for an
entry bounded by a concurrency, which has a gate and nothing adaptive to feed), the
upstream's verdict classified for the dispatcher exactly as the embeddings template
classifies it - a 429 a `RateLimitRetryException` with its `retry-after`, a 5xx or an
unreachable endpoint a `TransientErrorRetryException`, an exhausted account a
`QuotaExhaustedException`, a host DNS has never heard of uncorrectable - and the call
recorded on the response. `DecisionResponse` is an `LLMResponse` with no conversation, the
way `EmbeddingsResponse` is: the catalog id called, the billed input tokens, zero output,
the latency, the verdict, the served model and request id the endpoint reported, and the
answers by question id, read back by the shape asked (`choice`, `noul`, `score`). A job's
`JobResources.getDecisionClient` hands out the client wrapped in `ObservableDecisionClient`,
which records every call, successful or failed, on the `JobContext` beside the chat and
embeddings calls, so decision spend is counted and capped like any other model call and
the distributions a run produced stay on its record. `tools.deciding.DecisionCall` is the
job every decision is.

---

## Configuration

Nothing here reads an environment variable directly. Every tunable is a field on a
`Settings` class next to the code that reads it, assigned by the deployment's
`NucleoConfigurator` at startup (see [Settings and the configurator](../../PACKAGE.md)).
Credentials are named INDIRECTLY: each provider declares the id of its credential as a
constant, and the value behind it comes from the deployment's secret store (see
[Credentials](../../secrets/PACKAGE.md)); the constants and ids are listed in the guide.

| Knob | Purpose |
|---|---|
| `ModelSettings.mantleLaxProject` / `mantleStrictProject` | Bedrock Mantle project ids for `provider_data_share` and `none` retention |
| `ModelSettings.pickerClass` | Names the deployment's `ModelPicker` class; without one `DefaultModelPicker` ships - it serves the catalog's `pins` and falls back to the first configured entry of the grade (see [../models/PACKAGE.md](../models/PACKAGE.md)) |
| `ModelSettings.backendClass` | Catalog backend; defaults to `JsonModelsBackend`, which loads the deployment's file (the one `-Dnucleo.models` names, else `models.json` on the classpath, nearest first), and only without one the `META-INF/nucleo/seed_models.json` fragments the provider artifacts ship |
| `EmbeddingsClient.DIMENSIONS` | The canonical vector width for unpinned specs - a frozen constant of the storage contract, never a knob |

There is no company-wide token default of any kind: output size, thinking effort and the comfort context window are per-seat declarations with per-entry translations (see [../models/PACKAGE.md](../models/PACKAGE.md) and `tools/PACKAGE.md`).

---

## Thread Safety

| Component | Thread Safety |
|-----------|---------------|
| LLM clients | Thread-safe (one shared instance per spec, configured before publication; internal caches are concurrent) |
| `TokenBucketRateLimiter` | Thread-safe (all state guarded by the instance monitor) |
