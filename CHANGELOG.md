# Changelog

Every release of Nucleo, newest first. Released artifacts are on Maven Central under the
group `ai.redouble`; a published version is never changed in place.

## Unreleased

- Token accounting is one `TokenLedger` per call, in the new `ai.redouble.nucleo.harness.tokens`
  package: `ModelSpec.estimate(conversation)` returns the estimate the compaction trigger, the
  reservation and the client's pre-flight all read, and `LLMResponse.getUsage()` returns the
  usage the provider reported in the same shape. Counting is the provider module's: each
  `ClientProvider` supplies a `ContextEstimator` and a `TokenCounter` for its models, so tool
  calls, tool results, images, thinking blocks and cache counters are counted by the provider's
  own rules, and OpenAI's own endpoints count on `o200k_base`. Breaking: `LLMResponse` loses its
  scalar usage getters and setters (`getActualInputTokens`, `getActualOutputTokens`,
  `getCacheCreationInputTokens`, `getCacheReadInputTokens`, `getLastOutputTokens`, `hasCache`,
  `getCacheHitRate`, `setUsage(int, ...)`, `setAdditiveUsage`, `setActualInputTokens`);
  `ConversationContext.getTotalTokens`, `Message.getEstimatedTokens`, `Message.getTokens`,
  `Message.getActualTokens`, `TokenizerFactory` and `ContextWindowManager.absoluteLimit` are
  gone; `ModelBinding.getReservation()` returns the ledger; the token counters move to the new
  package and `Cl100kTokenCounter` becomes `TiktokenCounter`; `PreparedConversation` carries its
  registry section as a component; the pre-flight now reserves the thinking budget on a thinking
  model ([#1](https://github.com/redouble-ai/nucleo/issues/1)).
- An image is priced by its provider's published rule from the pixel size in its header: OpenAI's
  85 tokens plus 170 per 512-pixel tile after scaling, Anthropic's pixels over 750 after scaling
  with a ceiling of 1600, on Bedrock too. Breaking: `TiktokenCounter` takes four constructor
  arguments, the per-tile allowance is gone, and `Cost.reserved` is removed, since a reservation
  is a `TokenLedger` that prices itself ([#1](https://github.com/redouble-ai/nucleo/issues/1)).
- Compaction works on exchanges, an assistant turn with the turns that answer it, so a tool call
  and its result leave or stay together and the provider never sees one without the other; a
  tool result is detected by its block, its payload is what the summary model reads, LIGHT
  shrinks the payload inside the block, and every level checks the pairing before it replaces
  the conversation. A summary model's answer must be the asked-for object; an answer outside that
  shape is a failed summary, never prose that replaces content. Compaction measures the input
  alone: the room a call needs for its answer is the pre-flight's question. A conversation
  marked non-compactable is never compacted on any
  path; when it does not fit the model it fails with the new `NonCompactableContextException`,
  distinct from `ContextOverflowException`, which says compaction was tried and was not enough
  ([#1](https://github.com/redouble-ai/nucleo/issues/1)).
- A thinker's input reaches the model as the JSON of its fields inside the objective, and
  `ThinkerInput` has no rendering method to override
  ([#2](https://github.com/redouble-ai/nucleo/issues/2)).

## 0.1.1 (2026-10-02)

- Every published artifact carries a CycloneDX software bill of materials, attached as
  `<artifact>-<version>-cyclonedx.json` and signed with it.
- `NOTICE` carries the copyright and licence only. The statement on Redouble AI's patents
  is in the README's License section, where it can be explained.
- A model can no longer author an artifact: an artifact-typed field takes a reference, and
  the caller receives the registry's object (GHSA-m8pq-5898-5q8j).
- `WebFetchTool` declares its address policy itself and applies it to every redirect
  (GHSA-5h79-cgv5-c74w).
- An answer still missing a required field after its corrections fails the call
  ([#13](https://github.com/redouble-ai/nucleo/issues/13)).
- A cancelled job that then fails settles as cancelled and releases its caller
  ([#14](https://github.com/redouble-ai/nucleo/issues/14)).
- The demo's extract, pricing and decision runs read the corpus the demo ships, located by
  the engine; no request names a folder to read or a file to write, and the pricing report
  is the response alone. The page shows the folder in place of an input.

## 0.1 (2026-10-01)

The first public release.

- The runtime: jobs on virtual threads with capacity admission, typed tools, thinkers and
  doers, scopes and guardrails, byte-identical artifacts, prompt and skill registries, the
  model catalog with its discovery, and the observability bus.
- Provider modules: Anthropic, OpenAI and OpenAI-compatible endpoints, Azure AI Foundry,
  AWS Bedrock through Converse and through the Anthropic SDK, and System One decision
  models.
- Hosts: `nucleo-spring-boot-starter` for Spring Boot 4 and `nucleo-quarkus` for Quarkus,
  the latter building to a native image.
- `nucleo-examples`, a demo running the same work on both hosts, and the documentation
  site at [docs.redouble.ai](https://docs.redouble.ai/).

Known gaps, each tracked as an issue:

- [#1](https://github.com/redouble-ai/nucleo/issues/1): the `MAXIMUM` compaction level runs
  the same collapse as `AGGRESSIVE`, so a conversation whose non-compactable messages
  alone exceed the context limit is refused rather than made to fit.
- [#2](https://github.com/redouble-ai/nucleo/issues/2): `ThinkerInput.toLLMString` renders
  a thinker's input as ad-hoc text rather than strict JSON.

What is unstable at 0.1: the public API has not been through a second release, so a 0.2
may change signatures where a first consumer shows a better shape. Each such change will
be named here.
