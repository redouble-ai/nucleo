# Package: ai.redouble.nucleo.chat

A chat is a person talking to an agent in a browser, the answer streaming in as the agent
works. In most applications a chat is about something: a project, a support ticket, a case.
This package holds the parts of such a chat that are the same in every application, so one
compiled chat frontend can serve all of them: the message a browser sends, the shapes the
frontend lists scopes and conversations with, and the title a stored conversation is shown
under. The messages the server pushes back are in [Chat messages](messages/PACKAGE.md).

Nucleo ships no chat endpoint of its own. The application writes one, a websocket endpoint
over these shapes, and binds each conversation to its own entity, which this package calls
the conversation's scope. The conversation itself, its history and its persistence are the
runtime's ([Conversations](../harness/conversation/PACKAGE.md)).

## The shapes

- **`ScopedChatMessage`** is what the browser sends over a scoped chat socket. Its `type` is
  one of `message`, `load_conversation`, `ping` or `force_unlock`; it carries the scope id,
  the conversation id, the content, and for a forced unlock an optional reason. The scope id
  is required on a socket's first `message` and ignored afterwards, since a socket stays bound
  to the scope it opened with.
- **`ChatScopeInfo`** is one scope as the frontend sees it: the entity a conversation is bound
  to, projected into a shape that is the same in every application. Only `id` and `title` are
  guaranteed; a null field means the application has no such value.
- **`ChatConversationInfo`** is one stored conversation as the chat picker lists it: five
  typed fields, so the key names cannot drift between applications. The frontend addresses
  conversations by `conversationId`, the framework's identity.

## What the endpoint does

The endpoint is a view onto a durable conversation, and the conversation outlives it. The
contracts an endpoint implements against this package:

- **Principals ride every path.** The endpoint holds the socket's authenticated user and
  passes it into scope access checks and conversation loads; conversations are bound to their
  user everywhere, and an ownership refusal surfaces as an access-denied message rather than
  an internal error.
- **A conversation is stateless at rest and live only while a turn runs.**
  `conversationId` is durable; a workflow id lives exactly one turn; a job id one job
  inside a turn. Each inbound `message` either joins a running turn (the framework's
  `ConversationService.owningThinker` is how a turn another socket started is adopted) or
  starts a fresh turn that resumes the conversation from the store. Scope access is
  checked again at every turn start, so revocation takes effect at turn boundaries.
- **The CONNECTED frame opens every turn** and carries the durable `conversationId` and
  `scopeId` (`ConnectedStatus`), so a brand-new chat learns its real identity immediately.
  Any terminal failure of the turn job surfaces as an ERROR frame.
- **Detaching never cancels.** Closing the socket or loading a different conversation
  unsubscribes and clears; the turn finishes and persists, so a refresh does not lose the
  answer being generated. `force_unlock` is the single explicit kill, routed through
  `ConversationService.forceRelease`.
- **Replay ships the transcript and keeps the model's working material back.** A
  `load_conversation` replays the history to the socket, with no job, under the same
  ownership and scope checks; the conversation's main objective is not a message at all, so
  standing instructions never reach a client through any replay.

## Naming a conversation

`ConversationTitle.PROMPT` is what every store asks a model for, and
`ConversationTitle.clean` is what makes the answer displayable: the first non-empty line,
markdown emphasis and wrapping quotes stripped, whitespace collapsed, and null when
nothing usable is left, so a caller stores no title rather than an empty one. The prompt
asks for plain text and the cleanup enforces it, because a prompt is a request and a model
may answer with markdown anyway.

## How it works inside

The tests that pin the title cleanup and the CONNECTED frame are listed in
[Inside the chat surface](CHAT_INTERNALS.md), for those working on the runtime itself.
