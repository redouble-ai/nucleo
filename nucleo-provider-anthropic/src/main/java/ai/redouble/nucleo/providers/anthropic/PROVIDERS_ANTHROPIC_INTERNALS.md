# Inside the Anthropic provider

This page is for people working on the runtime itself. What the provider connects to, the
credential it needs and what it serves are in [the guide](PACKAGE.md); this page documents
its encoders - how the parts of a Nucleo message become Anthropic's native shapes
(`B = com.anthropic.models.messages.ContentBlockParam`); the encoder model they follow is
the parent
[encode/PACKAGE.md](../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/llm/encode/PACKAGE.md).

| Class | Role |
|-------|------|
| `AnthropicTextWrapper` | The one Anthropic text wrap: string -> `ContentBlockParam.ofText`. Injected into the shared text encoders (text/json/skill ride those base classes unchanged). |
| `AnthropicImageBlockEncoder` | Native base64 image; owns the MIME -> SDK media-type mapping, reused by the file encoder. |
| `AnthropicFileBlockEncoder` | PDF -> document param, image file -> image param, otherwise nothing. |
| `AnthropicToolUseBlockEncoder` | Native `ToolUseBlockParam` with the parsed input object. |
| `AnthropicToolResultBlockEncoder` | Native `ToolResultBlockParam` linked by tool-use id. |
| `AnthropicThinkingBlockEncoder` / `AnthropicRedactedThinkingBlockEncoder` | Replay thinking verbatim (signature / opaque payload round-trip). |

Each native encoder subclasses its logical base (`AnthropicImageBlockEncoder extends ImageBlockEncoder`) and builds `ContentBlockParam` directly without the text wrapper.
