# ai.redouble.nucleo.providers.anthropic

This provider calls Claude models on Anthropic's own API, through Anthropic's Java SDK. Put
the `nucleo-provider-anthropic` library on the classpath and provide one credential, and
every catalog entry under the provider key `anthropic-direct` can serve work.

## The credential

The API key, from `ANTHROPIC_API_KEY`, stored under the credential id `anthropic-api-key`.
It is the only thing the provider needs: [Set up and run the demo](../../../../../../../../../AGENTS.md)
says how a credential reaches the runtime.

## What it serves

Claude, with the features the Messages API carries: pictures and PDF documents sent as
themselves, tool calls in the API's own format, the model's extended or adaptive thinking
(set per catalog entry by `thinking_mode`, and passed back to the model on the next turn
exactly as it came), and prompt caching, with the number of cache breakpoints the entry
allows (`cache_breakpoints`).

The library ships a small catalog of its own: the current Claude model of each family on
this API, with its list prices and the limits of Anthropic's Start tier, used when the
deployment has no `models.json` of its own; the discovery writes the rest. Its entries are named for the channel, as in
`claude-opus-5-direct`, so the same model on Bedrock is a separate entry
([Grades, the catalog and the picker](../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/models/PACKAGE.md)
explains entries).

The discovery ([Discovering an account's models](../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/models/discovery/PACKAGE.md))
reads the API's model listing, which says which models the key may call. Anthropic lists
no rate limits; each response carries the account's limits in its `anthropic-ratelimit-*`
headers, and the discovery's one call per model writes them into the catalog.

## How it works inside

How each part of a message becomes Anthropic's native content blocks is in
[Inside the Anthropic provider](PROVIDERS_ANTHROPIC_INTERNALS.md), for those working on the
runtime itself.
