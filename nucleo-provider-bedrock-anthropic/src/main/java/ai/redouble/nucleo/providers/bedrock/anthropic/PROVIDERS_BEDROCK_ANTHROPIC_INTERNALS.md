# Inside Claude on Bedrock through the Anthropic SDK

This page is for people working on the runtime itself. What the artifact adds, the Mantle
projects and the credentials are in [the guide](PACKAGE.md); this page holds its classes,
how its entries link when a classpath lacks a provider, how a call's Mantle project is
chosen, and its tests.

| Class | Role |
|-------|------|
| `AnthropicBedrockProvider` | Key `anthropic-bedrock`, channel `bedrock`: Claude through AWS InvokeModel on `bedrock-runtime`, with the `us.` / `global.` inference-profile ids. |
| `AnthropicBedrockMantleProvider` | Key `anthropic-bedrock-mantle`, channel `mantle`: Claude through the native Messages API on the `bedrock-mantle` endpoint, with bare ids (`anthropic.claude-opus-5`). |
| `AnthropicBedrockSDKClient` | The Anthropic SDK client on its Bedrock backend, signing with the base's credentials and region (`BedrockClients`). |
| `AnthropicBedrockMantleSDKClient` | The same client on the Mantle backend; it chooses the Mantle project a call runs under (`resolveWorkspace`). |

Both providers implement the base's `BedrockProvider`, so they read the same credentials and
report the same region as every Bedrock surface, and both serve Claude ids alone
(`serves` is `AnthropicNaming.isClaude`) and claim them (`claims`, from `AnthropicProvider`), so a
catalog entry whose runtime-surface provider is absent and whose model is Claude links to
`anthropic-bedrock` before Converse, and to Converse when this artifact is absent. Mantle spells its
ids for its own endpoint (`addressing` is `bedrock-mantle`, bare ids that `bedrock-runtime` refuses),
so a Mantle entry links to the Mantle provider alone, and without this artifact it is unavailable. Their entries are `AnthropicModelSpec`s, the spec type the
direct Anthropic provider declares. They list through the base's `BedrockDiscovery`: the runtime
surface for `anthropic-bedrock`, the Mantle listing for `anthropic-bedrock-mantle`.

## Mantle projects

A Mantle call runs under a Mantle project, sent as the `anthropic-workspace-id` header, and the
project decides data retention. `AnthropicBedrockMantleSDKClient.resolveWorkspace` picks it per call,
as the last check before a request leaves the process: a model the compliance envelope refuses is
refused; a model that requires LAX (`requires_lax`, from the Mantle listing's `allowed_modes`) runs
under `ModelSettings.mantleLaxProject`, and is refused when none is configured; every other model runs
under `ModelSettings.mantleStrictProject`. A null project id sends no header and defers to the
account's retention setting.

## Catalog

The artifact ships its own `META-INF/nucleo/seed_models.json` fragment: the Claude entries on the
`bedrock` and `mantle` channels, with their `provider_defaults` (`spec_type: anthropic`, Anthropic's
cache multipliers and breakpoints).

## Tests

`AnthropicBedrockProviderModuleTest` pins the two providers' discovery beside the base's and their
fragment entries' spec type; `BedrockMantleWiringTest` the Mantle wiring. The base's pickers
(`BedrockOnlyModelPicker`, `ZdrOnlyModelPicker`) and the resolution gate are tested here too
(`AbstractModelPickerTest`, `ModelResolutionGateTest`), because their substitution cases are between
the two Claude routes this artifact adds.
