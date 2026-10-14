# Inside compaction

This page is for people working on the runtime itself: the failure discipline of the
ladder, how exchanges are cut and summarized, the pairing check, the summary job, and the
classes of the package. The guide is [Compaction](PACKAGE.md).

---

## The two numbers

`ContextWindowManager` governs a conversation with two numbers, each resolved from three
levels, and neither derivable from the provider's limits:

- **The comfort window** - the prompt size past which a model's answers are observed to
  degrade, well below its hard `max_context_tokens`. Resolution: the thinker's override
  (`ThinkerDeclaration.setComfortContextTokens`, or the thinker's own setter after
  construction), else the catalog entry's `comfort_context_tokens`, else
  `DEFAULT_COMFORT_CONTEXT_TOKENS` (128K, the point past which Anthropic models were seen
  to misbehave when the number was set; it stands until an entry says otherwise). The
  effective limit is the smaller of the comfort window and the hard context.
- **The compaction trigger** - the fraction of the effective limit at which compaction
  starts: the thinker's override (`ThinkerDeclaration.setCompactionTrigger`, or the
  thinker's own setter), else `DEFAULT_COMPACTION_TRIGGER` (0.92). A ratio with no model
  in it, so it has no catalog level.

Every decision reads one `TokenLedger`, the model's estimate of the conversation
(`ModelSpec.estimate`, [Tokens](../../tokens/PACKAGE.md)), and reads its `inputTokens()`
alone. Compaction shrinks input and nothing else, so the room a call needs for its answer is
no part of its arithmetic: if the input is small and the reserve is what does not fit, no
compaction can help, and the client's pre-flight is the check that says so. The trigger
compares the input to the effective limit times the trigger. A conversation still above the
comfort window after the ladder is accepted as long as its input fits `max_context_tokens`;
only that failure is a `ContextOverflowException`, and it names the input by part. A caller
that already holds the estimate passes it in, so one estimate serves one decision; the
thinker's per-turn check estimates once and hands the ledger to the warning, the trigger and
the ladder's first rung, and the ladder estimates once more per rung after compacting. The
summary jobs' own conversations are estimated by their bindings when they are priced; the
window manager is never applied to them, so compaction does not recurse.

## The ladder

`ensureFits` walks the levels until the conversation is back under the trigger. It is the
one ladder the framework has: the thinker loop's per-turn check
(`AbstractThinker.performCompactionLoop`) delegates here rather than walking levels of
its own. That check also warns the user, at every turn whose input is at or past 80% of the
effective limit, compactable or not, and on an overflow logs at DEBUG what filled the window, the input by
part, every objective block, every message's length as the model reads it and every
artifact, before the refusal propagates. A conversation that never resolved a model and has
no prior spec has nothing to account under and is returned unchanged; the compaction a
pre-flight overflow forces (`AbstractThinker.forceCompaction`) refuses such a conversation
with `IllegalStateException`, since an overflow was measured under some model and none is in
reach, and refuses a non-compactable one with `NonCompactableContextException`.

`@TODO` [gh-1](https://github.com/redouble-ai/nucleo/issues/1): MAXIMUM currently runs the
same collapse as AGGRESSIVE and guarantees nothing beyond it, so `ensureFits` cannot yet
promise what its name says - a conversation whose non-compactable messages alone exceed
the hard limit still ends in the overflow refusal. What a real MAXIMUM may touch that
AGGRESSIVE may not is the open design question tracked there.

### Failure discipline: best effort below MAXIMUM, loud at MAXIMUM

LIGHT, MODERATE and AGGRESSIVE are best-effort. A summary job that fails keeps the
original messages - the conversation is never left worse than it arrived, the failure
lands in the log with its cause, and the ladder moves on to the next level; the absolute
fit check above is the guard behind all of them. The compactor's internal rule that an
empty summary is a failed compaction (not a summary) exists so failed output never
REPLACES content.

MAXIMUM is not best-effort: it is the last line before the overflow refusal, so its
failures propagate as LLM-readable exceptions - a runtime `LLMReadable` cause travels
as itself, a checked one re-wrapped in its runtime sibling. An interrupt is never
best-effort at any level: a dying turn must stop compacting, so it re-raises the
interrupt flag and propagates.

## Exchanges, pairing and age

The compactor cuts the history into exchanges (`LLMContextCompactor.Exchange`): an assistant
turn and the outgoing turns that answer it, up to the next assistant turn. The outgoing
messages before the first assistant turn form the opening exchange, which has no assistant
turn and no pairing and is compacted message by message. A tool call lives on an assistant
turn as a `ToolUseBlock` and its result on the next outgoing turn as a `ToolResultBlock`
with the same id, and it is always the next turn: the thinker appends one batched results
message per assistant turn (`AbstractThinker`'s tool execution), and the framework's own
notes, a legend or a tool announcement, are appended after it. So the exchange is exactly
the pairing unit, and the newest exchange is the anchor.

Every level runs `requirePaired` over the list it built: every `tool_use` id answered in the
message right after it, every `tool_result` id issued in the message right before it. An
orphan on either side is a compactor bug, thrown as `IllegalStateException` naming the id.

- **LIGHT** summarizes each tool result of a compactable exchange before the anchor on its
  own, one `ToolResultTask` per `ToolResultBlock`, and the summary replaces the payload
  inside a copy of the block that keeps its id (`OutgoingMessage.copyWithBlocks`). Nothing
  moves; every pairing holds by construction.
- **MODERATE** summarizes each compactable exchange before the anchor as one `ExchangeTask`:
  the assistant turn with its calls and the turns that answered it become one summary
  message. The opening keeps its non-compactable messages in place and collapses the rest
  into one summary.
- **AGGRESSIVE** collapses every compactable exchange before the anchor, and the opening's
  compactable messages, into one summary of the whole history, with the non-compactable
  messages verbatim ahead of it. The anchor is summarized on its own as the trailing
  `[Recent exchange]` message when it may be compacted and any turn answering it carries a
  tool result, a legend or announcement after the results included; otherwise, a plain
  answer or a pinned exchange, it stays as it is.

A conversation of fewer than three messages, or one whose newest exchange has no outgoing
turn to anchor on, is returned as it is at every level. The summary model gets its bearings
from a window around the exchange's first message, the five messages before it and the four
after it (`extractContextWindow`), the exchange's own left out and each of the rest clipped to
300 characters, ahead of the exchange itself.

An exchange is compactable only when every message of it is: a preserved assistant turn pins
the results it called for, and preserved results pin the turn that called for them. An
exchange's position sets how terse its summary is asked to be:

| Exchange position | Summary detail |
|-------------------|----------------|
| Oldest quarter | Very concise - 1-2 lines |
| Second quarter | Concise - 2-3 lines |
| Third quarter | Moderate - 3-5 lines |
| Newest quarter | Most details preserved |
| The anchor | Never touched below AGGRESSIVE |

Every prompt the summary model reads is rendered through `ModelText`: a tool call with its
name and input, a tool result with its payload, never the placeholder form of
`getRawContent()`. Summaries run as parallel `CompactionJob`s.

## What compaction never touches

Compaction rewrites the MESSAGE list in place and nothing else: the main objective,
declared tools and artifact registry are not its business. Messages opt out
individually via `Message.setCompactable(false)` - user inputs and final assistant
answers are marked so by their owners and survive every level verbatim, as the same
instances. A conversation opts out entirely via
`ConversationContext.setCompactable(false)`. Every summary prompt orders artifact
references (`«artifact:type~id»`) preserved, so the registry's refs stay resolvable
after their surrounding prose is compacted.

## Every summary is a real LLM call

`CompactionJob` is an `AbstractModelDependentTool<Void, CompactionResult>`, the base every
exchange with a model in the runtime is built on: resolved through the picker at the
compactor's grade (`Grade.SMALL` unless the constructor says otherwise) at `Depth.STANDARD`
with a `STANDARD` answer declared, reserved, admitted and recorded like any other call, a
`UTILITY` job (the runtime's own work, never a tool a model called) with a 6-minute hung-call
ceiling. It wires its one
conversation once and re-wires a fresh binding per attempt, so the client's truncation
escalation and the correction turns reach a re-run. `CompactionResult` is the typed answer:
one required `summary` field. An answer that does not parse into that object, or parses
without the field, goes back to the model as a correction turn under `ResponseCorrection`'s
budget and fails the summary past it; a summary that parses but is blank is refused by
`requireSummary`. Either way prose outside the shape never replaces content, and a model
that cannot produce the shape fails the summary, which the level's failure rule then decides.

## Classes

| Class | Role |
|-------|------|
| `ContextWindowManager` | The two numbers, `effectiveLimit`, and the `ensureFits` ladder over one estimate per rung |
| `ContextCompactor` | The compaction strategy interface: in-place, level-driven |
| `LLMContextCompactor` | The shipped compactor: exchanges, the pairing check, age weighting, parallel jobs |
| `CompactionLevel` | LIGHT / MODERATE / AGGRESSIVE / MAXIMUM |
| `CompactionJob` | One summary as a model-dependent tool, corrected like any typed answer |
| `CompactionResult` | The LLM's typed answer |

Related packages: [conversation](../PACKAGE.md) (the messages being compacted, the
`compactable` flags, `ContextOverflowException`), [tokens](../../tokens/PACKAGE.md) (the
estimate the trigger and the fit check read, and `ModelText`), [schema](../../schema/PACKAGE.md)
(the `Summarizer` strategies that field-level summarization uses - a separate concern
from message-history compaction).
