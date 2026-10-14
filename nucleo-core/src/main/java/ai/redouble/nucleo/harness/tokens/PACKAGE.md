# Package: ai.redouble.nucleo.harness.tokens

Every call to a model is paid for in tokens, and three decisions hang on how many: whether a
conversation has grown enough to compact, how much of the model's per-minute budget a job
reserves before it runs, and whether the call fits the model's window at all. This package
is the one place those numbers come from, before the call as an estimate and after it as the
usage the provider reported, both in the same shape.

## The ledger

A `TokenLedger` is one call's tokens by kind: `inputTokens()`, `cacheReadTokens()`,
`cacheWriteTokens()`, `outputTokens()`, with `uncachedInputTokens()`, `cacheHitRate()`,
`cost()` and `meteredTokens()` derived from them. The same interface describes a call before
it is made and after it returns; the method names carry no "estimate" or "actual" because
provenance is a property of the instance, not of the facet. The estimate is what
`ModelSpec.estimate(conversation)` returns and what a `ModelBinding` wired to a conversation
holds as its reservation (a binding priced from a prompt or a flat count holds a
`DefaultTokenLedger` of those counts); the reported usage is `LLMResponse.getUsage()`.

Two absences are kept apart. A facet the provider does not report is null: a response whose
usage record carries no cache counters has null cache facets, a provider that returns no usage
record at all gives the response no ledger, and a derived facet is null when an operand it
needs is null. A zero is a fact, never an absence: OpenAI bills no cache writes, so its ledger
says zero. A facet a ledger does not implement refuses with `UnsupportedOperationException`
naming the provider and the facet. Every facet refuses by default on the interface, so a facet
added later is a default method and no provider's ledger breaks. `cacheHitRate()` is reads over
reads plus writes, 0.0 while a cache is being built and nothing has been read yet.

What a facet is worth depends on the side. On an estimate, `outputTokens()` is the reserve
the call books, the declared answer plus the depth's thinking budget clamped at the model's
ceiling, resolved from the seat's declarations the first time it is read, so an estimate of
the input alone asks nothing of them; `cost()` prices every input token at the full rate and is
the ceiling the call can reach; `inputByPart()` says how the input splits across the
`ContextPart`s (objective, tool definitions, messages, artifact registry), which is what an
overflow refusal names. On a reported usage, `cost()` is priced on what was reported, a floor
when a cache facet is missing, and `inputByPart()` refuses. `meteredTokens()` is what the
model's per-minute limit counts for the call, input plus output by default; which tokens a
limit meters is the provider's rule, and a provider's ledger overrides it.

## Which number each decision reads

| Decision | Reads | Against |
|----------|-------|---------|
| Compaction trigger | `inputTokens()` | the effective limit, the smaller of the comfort window and the model's context window, times the trigger |
| Compaction's absolute fit | `inputTokens()` | the model's context window |
| Client pre-flight | `inputTokens()` plus `outputTokens()` | the model's context window |
| Reservation | `meteredTokens()` | the model's per-minute limit |

Compaction reads input alone because it shrinks input and nothing else: if the input is small
and the answer's reserve is what does not fit, no compaction can help, and the pre-flight is
the check that says so.

## Who counts: the provider

Counting is the provider's knowledge. Tokenizers differ in density and some are not public;
an image is billed by area on one provider and by tiles on another; a thinking block is
replayed and billed on one provider and never sent on another; what a per-minute limit meters
differs too. So the provider module supplies the counting, through two methods on its
`ClientProvider`: `contextEstimator(spec)` and `tokenCounter(spec)`. Code never names a
provider; it asks the spec, `ModelSpec.estimate(conversation)` or `ModelSpec.tokenCounter()`,
and the spec finds its provider by key through the provider registry. A spec whose provider
module is not on the classpath estimates with the core default. No client and no credential is
involved, which is what lets the reservation run before any client is acquired.

A `ContextEstimator` renders the conversation exactly as the provider's client will send it
(`ConversationContext.render`, the pure rendering the client's own preparation also uses,
with the provider's formatter) and counts that rendering: the system text, every tool
definition, every turn with the response instructions its answer handler appends, the
registry section. Whether the model's native thinking is on changes those instructions, so the
estimate renders with the same decision the client will make, from the depth the conversation
declares; a conversation that declares no depth yet renders with thinking off.
`AbstractContextEstimator` is the walk every provider extends; `DefaultContextEstimator` is
that walk with the generic text formatter and the neutral counter. A provider overrides
`countBlock` for the block kinds it encodes or bills differently. What each shipped provider
sends and counts:

| Block | Core default and Bedrock Converse | Anthropic | OpenAI |
|-------|----------------------------------|-----------|--------|
| Text, JSON, POJO | sent as text, counted | same | same |
| Tool definition | text form, counted | native, counted as its text form | native, counted as its text form |
| Tool call, tool result | text form, counted | native, counted as their text form | native, counted as their text form |
| Image | the counter's image rule, floor 85; Claude on Converse by Anthropic's rule | pixels over 750 after scaling, at most 1600 | 85 plus 170 per 512-pixel tile after scaling |
| File | the counter's file rule | same | same |
| Thinking | not sent, not counted | replayed on every assistant turn, counted on the newest only | not sent, not counted |
| Redacted thinking | not sent, not counted | as thinking | replayed as a reasoning item, counted as its JSON |
| Skill | rendered body, counted | same | same |

The text form of a tool definition, call or result is what the generic encoders send and
`ModelText` renders: the name or the call id in brackets, then the JSON. A provider that sends
the block natively counts the same text, a few tokens of bracketing apart from the native
framing.

Densities: Anthropic counts text with `cl100k_base` at 1.10, since Claude's tokenizer is not
public and that is what Anthropic's usage reports show; OpenAI's own endpoints count on
`o200k_base` at 1.0, exactly; an OpenAI-compatible endpoint and every other provider count on
`cl100k_base` at 1.0; Bedrock Converse follows the family the wire id names, Claude at 1.10,
Cohere at 1.20, anything else neutral. `TiktokenCounter` is the counter class behind all of
them; `CharBasedTokenCounter` is the fallback with no encoding at all, a quarter of a token
per character with an image floor of 1000.

An image is priced by the provider's published rule from its pixel size, which
`ImageDimensions` reads from the header of a PNG, JPEG, GIF or WebP payload. OpenAI scales an
image to fit 2048 pixels on its long side and 768 on its short side, cuts it into 512-pixel
tiles, and charges 85 tokens plus 170 per tile. Anthropic, direct or on Bedrock, scales an
image to 1568 pixels on its long side and charges its pixel count over 750, at most 1600. An
image whose size cannot be read, and every image on the neutral counter, costs the counter's
floor or four tokens per KB of its bytes, whichever is more, and the floor alone when the bytes
do not decode; the floor is 85 on the neutral counter and 1600 on Claude's, so an unreadable
image errs high on Claude. A file costs 450 tokens per KB when it is a PDF, the counter's floor
or four tokens per KB when it is an image, 100 per KB otherwise, and 4500 when its bytes do not
decode; the file rule is the same on every shipped counter.

`ModelText` is the text a model reads for a block or a message, the form the generic encoders
send: a tool call with its name and input, a tool result with its payload. The default walk
counts it, and compaction shows it to the summary model. It is distinct from a message's raw
content, which is the short placeholder for logs and persistence.

## Classes

| Class | Role |
|-------|------|
| `TokenLedger` | One call's tokens by kind, estimate or reported usage; every facet refuses by default |
| `AbstractTokenLedger` | The counts, the derived facets and the lazily resolved output reserve; what a provider's ledger extends |
| `DefaultTokenLedger` | The ledger with no provider knowledge: every estimator's unless a provider overrides `newLedger`, and the usage record of the embeddings and decision clients |
| `ContextEstimator` | What a conversation will cost a model before the call |
| `AbstractContextEstimator` | The walk over the rendered conversation, with `countBlock` for a provider to override |
| `DefaultContextEstimator` | The walk with the generic formatter and the neutral counter |
| `ContextPart` | Objective, the palette's tool definitions, messages, artifact registry |
| `TurnPlace` | Where a block sits, user turn, earlier or newest assistant turn, for billing rules that depend on the turn; the registry section is never walked as a turn |
| `TokenCounter` | The density of a tokenizer: text, images, files |
| `TiktokenCounter` | A tiktoken encoding times a density, with the generic image and PDF rules; a provider's counter overrides the image rule |
| `CharBasedTokenCounter` | Characters over four, for a provider with no known tokenizer |
| `ImageDimensions` | The pixel size of an image from its header, what a provider's image rule prices |
| `ModelText` | A block or message as the model reads it |

Related: [conversation](../conversation/PACKAGE.md) for `render` and the output reserve,
[compaction](../conversation/compaction/PACKAGE.md) for the trigger and the fit check,
[llm](../llm/PACKAGE.md) for the provider SPI and the usage on a response,
[admission](../admission/PACKAGE.md) for what the reservation does with the metered amount.
