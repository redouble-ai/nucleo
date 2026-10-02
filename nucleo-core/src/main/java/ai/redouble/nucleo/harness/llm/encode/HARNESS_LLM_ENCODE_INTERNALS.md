# Inside per-block encoding

This page is for people working on the runtime itself: the encoder classes, how a client
looks them up and keeps them, and the invariants the encoders hold. The guide is
[Per-block encoding](PACKAGE.md).

---

## Why

A `ContentBlock` (text, image, tool-use, thinking, ...) has to become a provider-native object
before it goes on the wire: Anthropic `ContentBlockParam`, OpenAI Jackson `ObjectNode`, Bedrock
`ContentBlock`. That mapping used to live as a per-client `switch`/`instanceof` chain, which drifted
between clients and silently dropped block types (a non-exhaustive chain handled 2 of 11). Here each
block type owns one encoder class; a client only declares a `block.class -> Encoder.class` map and
overwrites the few entries it renders natively. Adding a client or a block type is a new class, never
an edit to a shared dispatch.

## Classes

| Class | Role |
|-------|------|
| `BlockEncoder<B>` | Abstract base: `encode(ContentBlock) -> B` (`null` = emit nothing), plus the injected `TextWrapper<B>` field |
| `TextWrapper<B>` | Functional interface: wraps a string into the provider's native text block |
| `TextBlockEncoder` / `JsonBlockEncoder` / `ToolUseBlockEncoder` / `ToolResultBlockEncoder` / `SkillBlockEncoder` / `ImageBlockEncoder` / `FileBlockEncoder` | Base text encoders: render the block's content string through the wrapper. Image/File default to a text placeholder for providers without that channel |
| `ThinkingBlockEncoder` / `RedactedThinkingBlockEncoder` | Base drop encoders: emit nothing (no thinking channel on the text path) |
| `ToolDefinitionBlockEncoder` | Renders the tool's one text form (`renderText`, also the string every token count measures); a client with a native tools API overrides to null and sweeps the blocks into its tools parameter instead |
| `PojoBlockEncoder` | Invariant guard: a `PojoBlock` reaching a client is a bug, thrown loudly |

The provider-native encoders live in the provider modules, next to the clients that register them:

| Package | Contents |
|-------|------|
| `ai.redouble.nucleo.providers.anthropic` | `AnthropicTextWrapper` + native `ContentBlockParam` encoders (image, file, tool-use, tool-result, thinking, redacted-thinking) and the tool-definition override to null (native tools API) |
| `ai.redouble.nucleo.providers.openai` | `OpenAITextWrapper` + native `ObjectNode` encoders (image, file, tool-use, tool-result) and the tool-definition override to null |
| `ai.redouble.nucleo.providers.bedrock` | `BedrockTextWrapper` + native `ContentBlock` encoders (image, file) |

## Lookup and lifecycle (on the client)

`AbstractLLMClient<B>` holds a `Map<Class<? extends ContentBlock>, Class<? extends BlockEncoder<B>>>`
built by `buildEncoders()` - declaration only, immutable. `encodeBlock(block)` resolves the encoder
class for `block.getClass()`, instantiates it once (cached per client via the no-arg constructor),
injects `textWrapper()`, and delegates. Class-to-class keeps the declaration shareable while each
client instance owns its own encoder instances: no encoder instance is shared between clients, and
because encoders are stateless apart from the wrapper, the threads calling one shared client use its
encoders safely. A block type with no registered encoder is a loud error, never a silent drop;
`BlockEncoderTest` asserts every permitted `ContentBlock` subtype has a base encoder.

## Invariants

- Per-message assembly (role dispatch, Anthropic cache-breakpoint placement, OpenAI text collapse,
  system-block accumulation) stays in the client - it is message-level, not per-block, state. Only
  the per-block encoding lives here.
- Encoders are stateless apart from the injected wrapper, which is provider-constant.
- A native (non-text) encoder must not call the wrapper; a text encoder must produce a
  `wrapper`-shaped block (the OpenAI text-collapse join depends on it).
