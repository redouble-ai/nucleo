# Inside the chat messages

This page is for people working on the runtime itself. What the frames are and how a client
reads them is in [Chat messages](PACKAGE.md).

## What is here

| Class | Role |
|---|---|
| `WebSocketMessage<T>` | The outgoing envelope: `type`, `timestamp`, `workflowId`, plus a `@JsonUnwrapped` payload whose properties merge into the top-level JSON. Payloads are POJOs so unwrapping applies; a Map payload nests under `data` instead (the `conversation_loaded` frame relies on exactly that). |
| `WebSocketMessageType` | Every frame type the clients validate against, serialized lowercase. |
| `StatusMessage` | The plain status/error payload: `status` + `message`. Used for errors, pong, unlocked, disconnected, chat_closed, history_complete. |
| `ConnectedStatus` | The CONNECTED frame's payload: `StatusMessage` plus the durable `conversationId` and `scopeId`. Additive on the wire - unwrapping flattens subclass properties, so clients validating only `status`/`message` keep working. Sent at every turn start so a brand-new chat learns its real identity immediately. |
| `IncomingMessage` | Jackson-polymorphic inbound shape, by `type`: `message`, `ping`, `subscribe`, `unsubscribe`, `list`, `force_unlock`. The scoped chat endpoints read `ai.redouble.nucleo.chat.ScopedChatMessage` instead. |

## Tests

`ConnectedStatusShapeTest` pins the unwrapped-subclass behavior through a Jackson mapper,
which is what every host codec is built on.
