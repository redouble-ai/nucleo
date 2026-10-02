# Inside the chat surface

This page is for people working on the runtime itself. What the chat shapes are for and what
an application's chat endpoint does with them is in [The chat protocol](PACKAGE.md).

## What is here

| Class | Role |
|---|---|
| `ScopedChatMessage` | The inbound message shape of a scoped chat socket: type (`message`, `load_conversation`, `ping`, `force_unlock`), scope id, conversation id, content, and the optional force-unlock reason. |
| `ChatScopeInfo` | One chat scope as the frontend sees it: the entity a conversation is bound to, projected into a shape that is the same in every application. Only `id` and `title` are guaranteed; a null field means the application has no such value. |
| `ChatConversationInfo` | One stored conversation as the chat picker lists it: five typed fields, so the key names cannot drift between modules. The frontend addresses conversations by `conversationId`, the framework's identity. |
| `ConversationTitle` | The title-generation prompt and its response cleanup, shared by every conversation save path. |
| `messages/` | The socket's wire vocabulary: the outgoing envelope and its payloads (see [messages/PACKAGE.md](messages/PACKAGE.md)). |

## Tests

`ConversationTitleTest` pins the cleanup contract. `ConnectedStatusShapeTest` (in
`messages`) pins the CONNECTED frame's unwrapped wire shape.
