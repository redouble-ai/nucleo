# Package: ai.redouble.nucleo.tools.thinking

A thinker is an agent: a model given an objective and a palette of tools, run in a loop until
it answers ([Tools, thinkers and doers](../PACKAGE.md)). Agents are asked for different
things - work out one answer to a question, hold a conversation with a person, make one call
to a model with no tools at all - and each is a different loop. This package has one base
class per shape. Pick the one that matches what the agent is for; everything else (the
declaration, the palette, the checks, artifacts, the read-only binding) is shared by all of
them.

| The agent | Extend |
|-----------|--------|
| Works toward one answer: a question in, a typed answer out | `SingleObjectiveThinker<I, O>` |
| Answers a person's messages in a conversation that lasts | `ChatThinker`, or `ReactiveThinker` for a shape of your own |
| Makes one model call with no tools, as a thinker | `AbstractToollessThinker<I, O>` |

An agent whose model writes nothing and only ranks options is a `DecisionThinker` ([What a
decision model is](../deciding/PACKAGE.md), which also says when to prefer it over the
thinkers here).

## Working toward one answer: `SingleObjectiveThinker`

This is the agent most applications write, and the one in [Your first
agent](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/agent/PACKAGE.md).
Its input extends `ThinkerInput`, which carries the question (`query`), how hard to work
(`depth`) and the artifacts it is handed (`artifactRefs`). Its answer extends `ThinkerOutput`,
which carries the model's reasoning and the artifacts the answer selected. The thinker turns
until the model gives a final answer that fits the answer class and passes the declared
checks, or until its iteration limit, and returns that answer as the job's result.

The model reads the input as data. The thinker puts the registered prompt and the input
side by side in a `ThinkerObjective`, and the conversation serializes that object to JSON:
a `prompt` field and an `input` field, the input being its own fields under their schema
names, with `@LLMContextIgnore` fields such as `artifactRefs` left out. A subclass adds the
fields its agent needs and nothing else; there is no rendering to write.

A few hooks change how a run starts and what it leaves behind:

- **`getSeededFollowupMessage()`** returns a message placed right after the objective, before
  the first turn: the last piece of context a model should read before it starts, such as a
  plan to follow. The objective leads with stable material; this message comes last and has
  the recency the objective lacks. It is placed only in a new conversation.
- **`setConversationId(id)`** resumes the conversation held under that id, which must exist,
  instead of starting a new one. The new input's question arrives as the next user message,
  and the objective stays the one the conversation began with, so a doer can put a challenge
  to an agent that already answered.
- **`transcript()`** returns, after a run, the whole conversation as text: every message,
  tool call and tool result in order. Code that holds the thinker after its job completed
  reads what the run did without the conversation store.

When your code submits the thinker directly, its final answer is also published as a
`ContentStreamEvent`, the answer's text or the answer as JSON, for a frontend watching the
run. A thinker called by another thinker returns its answer to the caller instead.

`SubThinker` is the `SingleObjectiveThinker` behind the `sub_thinker` tool, the sub-agent a
thinker's model can start on part of its problem ([Tools, thinkers and
doers](../PACKAGE.md)).

## A chat: `ChatThinker` and `ReactiveThinker`

A chat answers messages as a person sends them, over a conversation that lasts between
messages. `ReactiveThinker` is that loop. It takes no structured input: messages arrive
through `addMessage(String)`, and one run of the job, a turn, answers every message waiting
for it and then completes. A message sent while the model is working is added at the next
step, so a person can steer an answer in progress. A message that arrives after the turn has
closed is refused (`addMessage` returns false), and the caller starts a new turn, which
resumes the saved conversation. Its answer is text, streamed to the person.

`ChatThinker` is the ready-made chat: it keeps the conversation beyond the turn and streams
each final answer as a `ContentStreamEvent` with the artifacts of the conversation. Extend
`ReactiveThinker` directly only for a conversation of another kind, implementing
`initializeConversation`.

What a chat does differently from an agent working toward one answer:

- **A budget per message.** `setMaxIterationsPerMessage` limits the tool calls for one
  message, 25 unless set. When the budget runs out, one more call is made that must answer
  from what has been gathered, and say what remains unverified, so the person always gets an
  answer.
- **Its own depth.** With no input to carry one, a chat's effort is its own, `STANDARD` unless
  its constructor calls `setDepth`.
- **A failed exchange fails the turn and loses nothing.** The conversation is rolled back to
  the person's last message, a marker saying why the answer did not come is recorded in its
  place, and the turn ends with the failure. The next message starts a new turn over the saved
  conversation, and the model reads why the previous attempt produced nothing.
- **The strongest model, by declaration.** A chat that wants the best model the deployment
  serves declares `Grade.CEILING`; a chat with a narrower job declares its rung.

[The chat protocol](../../chat/PACKAGE.md) is how a host exposes a chat to a frontend.

## One call without tools: `AbstractToollessThinker`

Some work is a single model call - classify this, extract that - that still wants what a
thinker has: a declaration, a typed input, validation checks, artifacts, the read-only
binding. A looping thinker would pay for machinery the call never uses: instructions for tools
that do not exist, the envelope of a loop turn, a prompt cache written and never read.
`AbstractToollessThinker` drops the loop. The model is asked for the answer class itself, one
call per candidate answer. A check that refuses the answer sends the reason back as a
correction, up to `AbstractToollessThinker.MAX_GUARDRAIL_CORRECTIONS` times (two), after which
the run fails with the last refusal. It takes the answer class in its constructor, and
`getSystemPromptText()` is abstract. `addTool` throws: a tool handed to it could never be
offered to the model.

## How it works inside

The classes of this package seat by seat, and the contracts they keep - the declaration, the
pin reaching every call, the transcript, the per-turn palette, the pairing of tool results,
bounded corrections and the clean end of a chat turn - are in [Inside the thinker
families](TOOLS_THINKING_INTERNALS.md), for those working on the runtime itself.
