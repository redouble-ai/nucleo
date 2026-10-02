# Package: ai.redouble.nucleo.harness.conversation.compaction

Every call sends the whole conversation ([Conversations](../PACKAGE.md)), and every model
has a limit on how much it reads at once, its context window. A long agent run or a chat
that goes on for days reaches that limit. Well before it, answers get worse: a model given
a very long prompt starts to lose track of what it read.

Compaction makes room. When a conversation comes close to its limit, older messages are
replaced by summaries of them, written by a model, until the conversation fits again. The
messages you mark as essential stay word for word, and so do the instructions, the tools
and the artifacts. A thinker checks this before every call to its model, so you do not
call anything yourself; what you decide is where the limit sits and what must survive.

## When compaction starts

Two numbers decide it, and `ContextWindowManager` applies them:

- **The comfort window** is the prompt size past which a model's answers are seen to
  degrade, usually well below its hard limit. A thinker sets its own with
  `setComfortContextTokens` (on the thinker, or on its `ThinkerDeclaration`); otherwise
  the model's catalog entry gives it as `comfort_context_tokens`; otherwise it is
  `DEFAULT_COMFORT_CONTEXT_TOKENS`, 128,000 tokens. The limit Nucleo keeps to is the
  smaller of the comfort window and the model's hard limit.
- **The compaction trigger** is the share of that limit at which compaction begins:
  `setCompactionTrigger` on the thinker or its declaration, otherwise
  `DEFAULT_COMPACTION_TRIGGER`, 0.92.

With the defaults, on a model whose hard limit is larger than 128,000 tokens, compaction
starts once the conversation passes about 118,000 tokens.

## How much is summarized

Compaction goes up a ladder of four levels, `CompactionLevel`, and stops at the first
level after which the conversation is back under the trigger:

| Level | What is summarized |
|-------|--------------------|
| `LIGHT` | Only tool results; everything else stays as it was |
| `MODERATE` | Each exchange (the messages between two answers of the model) becomes one summary; the older the exchange, the shorter its summary |
| `AGGRESSIVE` | Everything that may be compacted becomes one summary of the whole conversation |
| `MAXIMUM` | The last level before the conversation is refused |

`@TODO` [gh-1](https://github.com/redouble-ai/nucleo/issues/1): `MAXIMUM` runs the same
collapse as `AGGRESSIVE` today, so it cannot yet guarantee a fit that `AGGRESSIVE` did not
reach.

The most recent message your side sent is left as it is below `AGGRESSIVE`; at
`AGGRESSIVE`, when it is a tool result, it gets a summary of its own. Below `MAXIMUM`, a
summary that fails keeps the original messages and the ladder moves to the next level, so
compaction never leaves a conversation worse than it found it. A failure at `MAXIMUM`
fails the call.

A conversation that is still above the comfort window after the ladder is accepted as long
as the whole call fits the model's hard limit, counting the room reserved for the answer.
When it does not fit, the call fails with `ContextOverflowException`, which the agent
above sees and can act on.

## What must survive

Compaction rewrites the list of messages and nothing else: the objective, the tools and
the artifact registry are never touched. Artifact references in summarized text are kept,
so every artifact stays reachable.

- **A message marked `setCompactable(false)` is kept word for word** at every level. The
  chat thinkers mark the user's messages and the model's final answers this way, so the
  dialogue itself survives and the tool output around it is what shrinks.
- **A conversation marked `setCompactable(false)`** on its `ConversationContext` is never
  compacted.

## What it costs

Every summary is a call to a model, run as a `CompactionJob` of its own: a job at the
`Grade.SMALL` grade unless the compactor is built with another, admitted against rate
limits, recorded and costed like any other call. At `LIGHT` and `MODERATE` the summaries
of the separate exchanges run in parallel.

## How it works inside

The failure rules of each level, how exchanges are cut into segments and weighted by age,
and the summary job are in
[Inside compaction](HARNESS_CONVERSATION_COMPACTION_INTERNALS.md), for those working on the
runtime itself.
