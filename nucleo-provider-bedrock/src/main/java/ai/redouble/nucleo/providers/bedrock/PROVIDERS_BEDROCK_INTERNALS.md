# Inside the Bedrock provider

This page is for people working on the runtime itself. What the provider connects to, the
credentials it needs and what it serves are in [the guide](PACKAGE.md); this page holds its
encoders, its transport and the detail of its account listings.

## Encoders

How the parts of a Nucleo message become Converse's native shapes
(`B = software.amazon.awssdk.services.bedrockruntime.model.ContentBlock`); the encoder
model they follow is the parent
[encode/PACKAGE.md](../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/llm/encode/PACKAGE.md).

| Class | Role |
|-------|------|
| `BedrockTextWrapper` | The one Bedrock text wrap: string -> `ContentBlock.text`. Injected into the shared text encoders. |
| `BedrockImageBlockEncoder` | Native `ContentBlock.image` carrying the decoded bytes. |
| `BedrockFileBlockEncoder` | A file as the file itself: a document type Converse reads (PDF, Word, Excel, CSV, HTML, text, Markdown) as a native `ContentBlock.document` with the decoded bytes and the file name reduced to the characters Converse accepts in a document name, an image file as a native `ContentBlock.image`; any other type produces nothing (`BedrockFileBlockEncoderTest`). |

Every other block type (tool-use, tool-result, JSON, skill) has no Converse native form and rides the base text encoders, so a Bedrock request carries them as text rather than dropping them.

## Transport

The runtime client (`BedrockClients.newRuntimeClient`) runs on the AWS SDK's own Apache transport, sized to the framework's HTTP pool (`HttpSettings.poolSize`) and timed like the framework's transport: 30 seconds to connect, 15 minutes for a response (`HttpConnectionPools.CONNECT_TIMEOUT_SECONDS`, `RESPONSE_TIMEOUT_MINUTES`), because a Converse call is silent until its whole answer is written and the SDK's default 30-second socket timeout cuts off any answer that takes longer.

## Catalog and discovery

The artifact ships `META-INF/nucleo/seed_models.json`, the fragment with a bootstrap kernel of its two surfaces' entries (Converse, Cohere embeddings) with AWS's published default quotas, loaded when the deployment has no catalog of its own; the add-on's fragment carries the Anthropic-on-Bedrock and Mantle entries, and the discovery authors the rest. Every Bedrock provider, the base's two and the add-on's two, shares `BedrockProvider`, which answers "configured" as a region and an identity both resolving, from the deployment's store records (`aws-access-key-id` as a whole pair, `aws-region`) or from AWS's own default chains (the standard variables, a profile, the process's role), reports the resolved region as its `connectionFacts()` (Bedrock is regional and the catalog's `us.`/`global.` profile ids resolve against the region's endpoint, so a wrong region fails every call with a 400 that names neither the region nor the model - the fact is how a status page shows it before the first call), and each implements `ModelDiscovery` through `BedrockDiscovery`:

- The runtime surface lists the control plane's foundation models and inference profiles (the `us.` / `global.` ids the runtime sends) in the configured region. A foundation id is listed only when its inference types include on-demand: an id served only through provisioned throughput or through a profile refuses the plain invocation the runtime makes, and the profile the region lists for it is what the account reaches (`BedrockDiscovery.onDemand`). Each listing carries the model's input and output modalities as AWS spells them (taken as strings, since the SDK's enum knows only text, image and embedding and would read a speech model as one that takes nothing) and its lifecycle (retired). Of the platform's four keys, the add-on's two Anthropic ones serve Claude ids alone and the Cohere embeddings key serves `cohere.embed` ids alone (`ClientProvider.serves`); Converse serves every vendor. So a Titan or Nova embeddings model, whose request shape none of the clients speak, and a vendor the Mantle catalog lists but the legacy runtime does not know, are reported as listed with no client rather than classified into an entry that would fail on its first call. It takes each entry's tokens- and requests-per-minute from Service Quotas in the configured region, because Bedrock sends no rate-limit headers. A quota is matched by the name AWS gives it: the wire id's prefix picks the family (global cross-region, cross-region, on-demand) and the provider and model names complete it, with and without AWS's " V1" suffix. A model AWS spells differently in its quota names matches nothing, keeps its seed limit, and is reported as such.
- The Mantle surface lists `GET /v1/models` on the `bedrock-mantle` endpoint, signed with SigV4 under the same credentials; the add-on's Mantle provider lists through it (`BedrockDiscovery.mantleModels`), since the listing is an AWS call and needs no Anthropic SDK. It is the only surface carrying each model's `allowed_modes`, which is where `requires_lax` comes from: a model whose allowed modes exclude `none` cannot run at zero retention, so the runtime routes it through its LAX project (Fable 5 today: `aws_review` and `provider_data_share` allowed, `none` not).

This is why the artifact depends on the `bedrock` control-plane and `servicequotas` SDK clients beside `bedrockruntime`.
