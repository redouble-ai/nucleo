# Package: ai.redouble.nucleo.harness.conversation

A model remembers nothing between calls. Every request has to carry everything the model
is to know: its instructions, what has been said so far, the tools it may call and what
those tools returned. An agent answering one question may call its model a dozen times,
and each call sends the whole exchange again with one more round added.

In Nucleo that exchange is a `ConversationContext`, the conversation. It is the state of an
agent's work, and every call to the model is built from it. It outlives a single call
because the next call is made from it. It can outlive a job: a doer hands it from one
thinker to the next, and the next continues where the first stopped. It can outlive the
process: saved to a store, it is resumed after a redeploy, or tomorrow, by the same user.

A thinker ([The thinker families](../../tools/thinking/PACKAGE.md)) creates and manages its
conversation for you. You meet this package when you decide what the model is always told,
what is worth caching, or how a conversation is kept beyond one run.

## What a conversation holds

- **The main objective**: the standing instructions, which a provider calls the system
  prompt. The model reads it before anything else on every call.
- **The messages**, in order. An `OutgoingMessage` is what your side sends: a question, the
  results of the tools the model called. An `IncomingMessage` is what the model answered.
- **The tools** declared to the conversation, which the model may call.
- **The artifact registry**: the artifacts the tools have produced, kept exactly as they
  came out ([Artifacts: data the model cannot alter](../artifacts/PACKAGE.md)).
- **What its seat asked for**: the grade of model, the depth of thinking and the size of
  the answer ([The catalog, grades and the picker](../models/PACKAGE.md)).

A message is a list of content blocks, one per part: `TextBlock` for text, `PojoBlock` for
a Java object, `ImageBlock` and `FileBlock` for attachments, `ToolUseBlock` for a call the
model made and `ToolResultBlock` for what the tool returned, `ThinkingBlock` for the
model's own reasoning. They are declared in `ContentBlocks`, and each provider's client
turns every kind into that provider's format.

## The objective: what the model is always told

The objective is a set of named slots. You fill one with `putMainObjective`:

```java
conversation.putMainObjective("task", "Answer customer questions about their orders.");
```

Putting a key that is already there replaces its value where it stands, so any part of
your code may put the same slot again without checking whether someone already did. A new
key adds a slot after the others. The keys stay on your side; the model sees the values,
in order.

The objective stops changing once the conversation has been sent to a model. The start of
a request is what providers cache (below), and an instruction that changed halfway would
throw that cache away on every later call. After the first call, putting a slot again with
the same text costs nothing, and putting anything else is ignored and logged as an error
with the stack trace of the code that tried. To change what the model should do in the
middle of a conversation, say it in a message, where the model reads it as news.

What goes into the objective is instruction: the task, framework guidance, and the
configuration of your application, even when an administrator typed it into a settings
form. What stays out is data: retrieved documents, search results, the state of this turn.
Data rides the user messages, where the model weighs it as input and is not bound to obey
it, and where it does not disturb the cached start of the request.

## How the tools and the answer are shown to the model

A thinker declares its tools with `addTools`, and a tool is declared once: declaring the
same name again changes nothing. At the first call, the tools join the objective, preceded
by a short guide to the cost annotation each tool carries. A tool declared later, once the
objective has been sent, arrives as a message at that point ("Additional tools now
available:"), so the start of the request stays the same and the record shows the tool
appearing when it appeared.

How a definition reaches the model depends on the provider. Anthropic and OpenAI have a
tools parameter of their own, and their clients send every definition there; the model
answers with a `ToolUseBlock` under an id the provider assigns. The Bedrock Converse client
writes the definitions into the text instead, the model writes its tool calls into its
answer, and Nucleo gives each call an id so its result can be matched to it.

The shape of the answer you declared ([Answers as Java objects](../schema/PACKAGE.md)) is
appended to the outgoing message. Two fields of it are left out when they would make the
model do needless work: the field for tool calls, when the provider has native tool calls,
and the field for the model's reasoning, when the model thinks natively and its reasoning
arrives in a thinking block.

## What one call sends

When a client is about to call its model, it asks the conversation for
`prepareMessagesForLLM`, which returns a `PreparedConversation`: the objective rendered as
text, the tool definitions, and the turns, each one a user turn or an assistant turn. The
artifact registry comes last, as a user turn, because it is data. Objects are serialized
on the way, with every artifact replaced by its reference.

That split is made once, here, for every provider. A client writes the objective into its
provider's place for instructions (Anthropic's `system` parameter, Converse's system
blocks, a leading system message on OpenAI) and the turns as turns. Because the decision is
never made twice, no provider can end up with an instruction duplicated or dropped.
[Model clients and rate limits](../llm/PACKAGE.md) is the page about the clients.

## Prompt caching

Most of each request repeats the one before it: the same instructions, the same tools, the
same history, with one more round at the end. Providers can keep the processed start of a
request for a few minutes, and a later request that begins with exactly the same content
reads it from that cache. The cached part costs a fraction of the normal price and comes
back faster. For an agent that calls its model a dozen times per question, caching is most
of its input bill.

On Anthropic (direct or on Bedrock) you mark what is cached, with up to four marks per
request. Anthropic bills a cache write at 1.25 times the input price and a cache read at a
tenth of it, so a prefix pays for itself from its second use.

- **The objective is cached by default.** One mark on the end of the instructions covers
  the tool definitions, the skills and the objective together.
  `setCacheMainObjective(false)` turns it off for a conversation whose instructions are
  used only once.
- **A message is cached when it is marked.** `message.setCache(true)` places a mark at
  that message, and marks go to the marked messages in order, oldest first, until the four
  are used. A thinker marks every message it sends when `setCacheAllMessages(true)` is set
  on it.

OpenAI caches the start of a request on its own, with nothing to mark and no charge for
the write. Whatever the provider, the fixed objective is what keeps the cache valid from
one call to the next.

Each `LLMResponse` reports what the cache did for that call: `getCacheCreationInputTokens()`
for what was written, `getCacheReadInputTokens()` for what was read, and
`getCacheHitRate()`. The price of cached tokens comes from the model's catalog entry.

## How much the model may answer

Every call sends a limit on the length of the answer, and Nucleo reserves the same number
against the provider's rate limit before the call goes out. The limit comes from the seat:
the answer size the thinker declared, translated by the model's catalog entry, or a limit
set on the single message. There is no default: a conversation that declares no size fails
with a message that says so. The limit never exceeds what the model can produce, and it
includes room for the model's thinking when the call reasons.

When an answer is cut off at the limit, the call is repeated once with the model's full
output ceiling. An answer that was already cut at the model's ceiling fails, since a
larger limit does not exist.

## When a conversation grows too long

A model reads only so much at once, and it answers worse well before that limit. Nucleo
estimates the size of the conversation before each call (`getTotalTokens`), and when it
comes close to the limit, older parts are summarized to make room:
[Compaction](compaction/PACKAGE.md). You choose what must survive word for word. A message
marked `setCompactable(false)` is never summarized; the chat thinkers mark the user's
messages and the final answers that way. `ConversationContext.setCompactable(false)`
keeps a whole conversation as it is. A conversation that cannot fit even after the
strongest compaction fails with `ContextOverflowException`, which the parent agent sees
and can act on.

## Keeping a conversation beyond one run

Every thinker's conversation is kept by `ConversationService`, and a thinker says which
conversation it works on in one of three ways:

- **By default** its conversation is temporary: new, named after the thinker's job, and
  gone once that job's retention ends.
- **`mintConversationId()`** starts a new durable conversation, for a chat that will be
  resumed. It is written to the store at the first user message, titled from that message.
- **`setConversationId(id)`** resumes an existing one, and fails if no store holds it.

One job owns a conversation at a time. A second thinker that asks for it waits until the
first releases it, up to 30 seconds, so two writers never collide and a handed-over
conversation continues exactly where the previous thinker left it.

To keep conversations across restarts, give the service a store:
`setPersistentStore` for a durable one, and `setSessionStore` for a fast one consulted
first. Both are interfaces your application implements over the storage it already has;
every call names the user acting, and a conversation belongs to its user. The service saves
at each boundary of the exchange: a user message in, an answer out, or an exchange that
ended abnormally. A restored conversation brings back its messages, objective, tools,
artifacts and declarations; the thinker that resumes it supplies the rest, and the next
call picks its model again.

## Reading a conversation as text

`Transcript.render(conversation)` writes a conversation out for a person or a judge: each
message under its role, tool calls with their arguments, tool results against the call
they answer. A thinker keeps its last run's transcript as `transcript()`, and a benchmark
shows it to the judge.

## How it works inside

The channel contract, the objective map and its freeze, the cache breakpoint loop, token
accounting, the output budget chain, persistence and ownership are in
[Inside conversations](HARNESS_CONVERSATION_INTERNALS.md), for those working on the runtime
itself.
