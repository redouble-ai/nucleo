# Inside the thinker families

This page is for people working on the runtime itself: the classes of
`ai.redouble.nucleo.tools.thinking` seat by seat and the contracts local to them. The guide
for choosing and writing a thinker is [The thinker families](PACKAGE.md); the doctrine the
families share - what the model is shown, how tools are offered, when an answer is accepted -
is in [Tools, thinkers and doers](../PACKAGE.md) and [Inside tools, thinkers and
doers](../TOOLS_INTERNALS.md).

## The family

| Class | Seat |
|-------|------|
| `Thinker` / `AbstractThinker<I,O>` | The LLM loop over a `ToolRegistry` and a `ConversationContext` from `ConversationService`: per-turn reconcile hooks, tool definition blocks, two-phase parallel tool execution (every call of a turn submitted before any is awaited; a `ConversationAware` or `ToolRegistryAware` tool completes before the next call is submitted) with the tool_use/tool_result pairing invariant, compaction, artifact custody, the read-only binding. |
| `SingleObjectiveThinker<I,O>` | Goal-directed: one objective, iterations until a final answer, validation guardrails on the candidate answer, artifact resolution, the final answer of a top-level thinker streamed as one done `ContentStreamEvent` chunk (its text when it is a string, else the typed answer as JSON), `submitThinkingCall` as the scripted-turn test seam. |
| `SubThinker` | Runtime delegate any thinker can spawn: inherits the parent's provider INSTANCES and the parent's grade, captures the parent's reconciled tool and skill catalogs at launch so its own `request_tools`/`request_skill` doors offer exactly what the parent's did, answers with `SubThinkerOutput` at its own `STANDARD` rung, and raises its `delegationThreshold()` to THOROUGH. |
| `ReactiveThinker` | One chat turn over a durable conversation: the `Inbox` accept-or-refuse handshake, injection draining, the closing turn at the iteration cap, and the abnormal-end discipline (roll back to the last user utterance, record the outcome as a marker). |
| `ChatThinker` | The durable-chat seat over `ReactiveThinker`: marks the conversation non-temporary and streams the final answer as content events. |
| `AbstractToollessThinker<I,O>` | One call, no loop, no tool definitions: the answer schema is the output POJO's own, guardrail refusals return as correction turns under `MAX_GUARDRAIL_CORRECTIONS`, and `addTool` is refused outright. |
| `LLMCall<R>` | The one dispatched LLM exchange every thinker turn is: the conversation's seat (grade or pin) at requirements time, `ResponseCorrection` for parse/validation feedback, the truncation escalation, a package-private `client(resources)` seam for tests. |
| `ToolHub` | Process-wide singleton: system-wide tools, package scans cached forever, the compatible catalog resolved once per thinker class (widened by hot registration), and lazy admission for `request_tools` / `request_skill` - a guardrail's denial travels to the model as the reason, a guardrail's own crash as an internal error, never as a verdict. |
| `ThinkingResponseHandler<T>` | The answer-shape boundary: parses native blocks or the JSON envelope, keeps every tool_use as a `ToolCall` (unresolvable ones carry their parse error so no id is orphaned), mints ids for text-parsed calls, and refuses any answer the declared type cannot hold with a correctable refusal composed from the contract, never from the payload. |
| `ThinkerDeclaration` | The compile-enforced seat declaration: grade plus answer size, required by every thinker constructor. |
| `ThinkingResponse<T>` | The envelope: `final_answer`, `answer`, `tool_calls`, reasoning. |
| `ThinkerInput` / `ThinkerOutput` / `ThinkerObjective` / `ArtifactThinkerOutput` / `VoidThinkerInput` / `VoidThinkerOutput` / `SubThinkerInput` / `SubThinkerOutput` | The I/O shapes: input carries depth and conveyed `artifactRefs`; outputs carry reasoning and artifact custody. |

## Contracts worth naming

- **Every thinker declares.** The `ThinkerDeclaration` (grade + answer size) is a
  constructor argument on every family member; there is no undeclared thinker.
- **The pin reaches every call.** `newLLMCall` is the one factory every family builds its
  calls through; pinning a thinker pins its whole conversation to one entry, which is what
  `Benchmark` races (`ThinkerPinTest`).
- **A run leaves its transcript.** `transcript()` is the last run's conversation as
  `Transcript` text - every message, tool call and tool result in order - taken as the run
  ended, before the conversation went back to the service; null before the first run. A
  caller holding the thinker after its job completed reads what the run did without the
  conversation store; `Benchmark` hands it to the judge (`TranscriptTest`, `BenchmarkTest`).
- **The palette is per-turn state.** `reconcileToolRegistry()` runs before every turn's
  definitions are built and is idempotent; the read-only sweep runs after it, and the
  per-call read-only check runs beside `validateToolCall` at submission
  (`ThinkerPaletteContractTest`, `ReadOnlyPaletteTest`).
- **Results always pair.** A turn with N tool_use blocks produces a results turn with N
  tool_result blocks, however the tools fail; unanswered ids are backfilled at
  reconciliation (`ToolResultReconciliationTest`,
  `ThinkingResponseHandlerToolUseTest`).
- **Corrections are bounded and typed.** Parse and required-field failures go back to the
  model under `ResponseCorrection`'s budget; on exhaustion either failure surfaces, a parse
  failure as `JsonParseException` and a missing required field as
  `ResponseValidationException`, so no caller receives an answer lacking a required field
  (`LLMCallCorrectionTest`, `AbstractToolCorrectionTest`).
- **The turn ends cleanly or says why.** `ReactiveThinker` rolls an abnormal end back to
  the last user utterance and records the LLM-readable reason as a marker, so a resumed
  model never sees a question hanging unanswered
  (`ReactiveThinkerAbnormalEndTest`, `ReactiveThinkerInboxTest`).
