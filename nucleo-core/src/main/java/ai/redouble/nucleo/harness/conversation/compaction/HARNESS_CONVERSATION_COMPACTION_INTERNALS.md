# Inside compaction

This page is for people working on the runtime itself: the failure discipline of the
ladder, how segments are formed and summarized, the summary job, and the classes of the
package. The guide is [Compaction](PACKAGE.md).

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

Compaction keeps a conversation under the effective limit. A conversation still above
the comfort window afterwards is accepted as long as the whole call fits the hard
context: the absolute limit is `max_context_tokens` minus the seat's `outputReserve`
(the same reserve the wire carries), and only that failure is a
`ContextOverflowException`.

## The ladder

`ensureFits` walks the levels until the conversation is back under the trigger. It is the
one ladder the framework has: the thinker loop's per-turn check
(`AbstractThinker.performCompactionLoop`) delegates here rather than walking levels of
its own.

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

## Segments and age

The compactor splits history into logical segments - the outgoing messages between
assistant responses - so tool call sequences and their results stay together and causal
relationships survive summarization. Segment summaries run as parallel `CompactionJob`s.

A segment's position sets how terse its summary is asked to be:

| Segment position | Summary detail |
|------------------|----------------|
| Oldest quarter | Very concise - 1-2 lines |
| Second quarter | Concise - 2-3 lines |
| Third quarter | Moderate - 3-5 lines |
| Newest quarter | Most details preserved |
| Latest interaction | Never touched below AGGRESSIVE |

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

`CompactionJob` is an `AbstractJob<CompactionResult>`: resolved through the picker at
the compactor's grade (`Grade.SMALL` unless the constructor says otherwise), reserved,
admitted and recorded like any other call, with a 6-minute hung-call ceiling. It builds
its one conversation once and re-wires a fresh binding per attempt, so the client's
truncation escalation reaches a re-run. `CompactionResult` is the typed answer: one
required `summary` field.

## Classes

| Class | Role |
|-------|------|
| `ContextWindowManager` | The two numbers, `effectiveLimit`/`absoluteLimit`, and the `ensureFits` ladder |
| `ContextCompactor` | The compaction strategy interface: in-place, level-driven |
| `LLMContextCompactor` | The shipped compactor: segments, age weighting, parallel jobs |
| `CompactionLevel` | LIGHT / MODERATE / AGGRESSIVE / MAXIMUM |
| `CompactionJob` | One summary as a first-class job |
| `CompactionResult` | The LLM's typed answer |

Related packages: [conversation](../PACKAGE.md) (the messages being compacted, the
`compactable` flags, `ContextOverflowException`), [schema](../../schema/PACKAGE.md)
(the `Summarizer` strategies that field-level summarization uses - a separate concern
from message-history compaction).
