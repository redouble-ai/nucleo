# Package: ai.redouble.nucleo.chat.messages

What travels over the socket while a person chats with an agent: the frames a server pushes
to the browser as the agent works, such as streamed answer text, progress, references and
titles, and the shape a client sends back. They are defined once here, so every endpoint and
every client renders the same conversation the same way. Scoped chats, bound to one business
record, add their own inbound message in [The chat protocol](../PACKAGE.md).

## The frame

Every frame is a `WebSocketMessage<T>`: an envelope of `type`, `timestamp` and `workflowId`,
and a payload whose properties are merged into the top level of the JSON (`@JsonUnwrapped`),
so a client reads one flat object:

```
{
  "type": "connected",
  "timestamp": ...,
  "workflowId": "abc-123",
  "status": "...",
  "message": "...",
  "conversationId": "...",
  "scopeId": 42
}
```

The payload is a bean so that the merge applies; a `Map` payload is not merged and nests under
`data` instead, which the `conversation_loaded` frame relies on. `WebSocketMessage.create(type,
workflowId, payload)` builds a frame, and `WebSocketMessage.simple(type, workflowId)` one with
no payload.

`WebSocketMessageType` names every frame type the clients validate against, written in lower
case on the wire: `stream`, `error`, `notification`, `status`, `progress`, `connected`,
`disconnected`, `pong`, `unlocked`, `info`, `message`, `subscriptions`, `chat_closed`,
`conversation_loaded`, `history_message`, `history_complete`, `workflow_complete`,
`workflow_failed`, `message_complete`, `message_failed`, `agent_processing`, `agent_idle`.

## The payloads

- **`StatusMessage`** is the plain `status` and `message` pair, used for errors, pong,
  unlocked, disconnected, chat_closed and history_complete.
- **`ConnectedStatus`** is the CONNECTED frame's payload: a `StatusMessage` plus the durable
  `conversationId` and `scopeId`. It is sent at every turn start, so a brand-new chat learns
  its real identity immediately, and it is additive on the wire: clients that check only
  `status` and `message` keep working.

## What a client sends

`IncomingMessage` is the inbound shape of a socket that follows workflows, read by its `type`:
`message` (content, and the workflow id it belongs to), `ping`, `subscribe` and `unsubscribe`
(to a workflow's progress, by workflow id), `list` (the current subscriptions) and
`force_unlock` (with an optional reason). A scoped chat endpoint reads
`ai.redouble.nucleo.chat.ScopedChatMessage` instead.

## Changing the wire

Deployed chat clients validate frames with top-level `typeof` checks per type. Changing the
shape of a payload class breaks them; adding a property to a merged payload bean does not,
because old validators ignore keys they do not know.

## How it works inside

The test that pins the merged wire shape is named in
[Inside the chat messages](CHAT_MESSAGES_INTERNALS.md), for those working on the runtime
itself.
