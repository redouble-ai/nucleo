# Package: ai.redouble.nucleo.harness.llm.encode

A message in a conversation is a list of parts, its content blocks: text, a Java object
already turned into JSON, an image, a file, a tool call, a tool result, the model's thinking
([Conversations](../../conversation/PACKAGE.md)). Every provider writes each part in a
format of its own. An image is one JSON shape for Anthropic, another for Bedrock Converse
and a third for OpenAI, and a provider without a place for some part needs it written out
as text instead.

This package is where that translation lives, one class per kind of part. An encoder, a
`BlockEncoder`, takes one content block and returns the provider's own object for it. The
shared encoders here write every kind of part as text; a provider module adds encoders for
the parts it can carry natively and uses the shared ones for the rest. No client contains
translation code of its own, so supporting a new provider means writing its encoders,
without touching how messages are built.

## How a provider chooses its encoders

A client declares, in `buildEncoders()`, which encoder class handles each kind of block. It
starts from the defaults of `AbstractLLMClient`, which map every kind to a shared encoder
here, and replaces only the entries it renders natively. Anthropic, for instance, replaces
the image encoder with `AnthropicImageBlockEncoder`, a subclass of `ImageBlockEncoder` that
builds Anthropic's own image block.

The text that the shared encoders write still has to become the provider's text object.
That last step is a `TextWrapper`, one per provider, which the client supplies through
`textWrapper()` and every text encoder calls. It is written once per provider, and the
shared encoders stay free of any provider knowledge.

What the shared encoders do with each kind of part:

- **Text, JSON, tool calls, tool results and skills** are written as text.
- **Images and files** become a short placeholder in the text, for a provider that cannot
  carry them; the provider modules for Anthropic, OpenAI and Bedrock carry them natively.
- **Thinking** is left out: only a provider with a thinking channel sends it back, and
  Anthropic's encoders do.
- **Tool definitions** are written as text, the same text every token count of a definition
  measures. A provider with a tools parameter of its own writes nothing here and sends the
  definitions through that parameter.
- **A Java object** that reaches an encoder is a bug, since it is turned into JSON before
  any client sees it, and it fails loudly.

Every kind of block has an encoder, so a client cannot silently drop a part: at worst the
part arrives as text.

## How it works inside

The table of encoder classes, the native encoders in each provider module, how a client
instantiates and keeps its encoders, and the invariants the encoders hold are in
[Inside per-block encoding](HARNESS_LLM_ENCODE_INTERNALS.md), for those working on the
runtime itself.
