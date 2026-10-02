# Changelog

Every release of Nucleo, newest first. Released artifacts are on Maven Central under the
group `ai.redouble`; a published version is never changed in place.

## Unreleased

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
