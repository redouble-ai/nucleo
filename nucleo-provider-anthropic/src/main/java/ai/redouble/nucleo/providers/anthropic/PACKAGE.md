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

## How it counts tokens

Claude's tokenizer is not public, so the provider counts text with `cl100k_base` at a density
of 1.10, the ratio Anthropic's usage reports show, and prices an image by Anthropic's rule:
scaled to 1568 pixels on its long side, its pixel count over 750, at most 1600 tokens. An
image whose size cannot be read from its header costs 1600 or four tokens per KB of its bytes,
whichever is more, so the estimate errs high; a PDF costs 450 tokens per KB. Tool definitions,
tool calls and tool results go to Anthropic as native blocks and are counted as their text
form, the name or call id with the JSON they carry.
A thinking block is replayed on every assistant turn and counted on the newest one only, the
one Anthropic bills. The same counting serves every channel the Anthropic SDK reaches, direct
and on Bedrock. A response's usage sums Anthropic's three input counters, the uncached input
plus the cache writes plus the cache reads, into the total the model read, with the cache
counters kept as facets of their own; a cache counter the response does not carry adds nothing
to the total and stays null as a facet. How the runtime uses these numbers is in
[Tokens](../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/tokens/PACKAGE.md).

## How it works inside

How each part of a message becomes Anthropic's native content blocks is in
[Inside the Anthropic provider](PROVIDERS_ANTHROPIC_INTERNALS.md), for those working on the
runtime itself.
