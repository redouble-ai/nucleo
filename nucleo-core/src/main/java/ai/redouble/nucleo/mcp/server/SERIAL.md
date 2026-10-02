# Schema dialects

Every tool a Nucleo server offers comes with a JSON Schema for its arguments and its result:
the contract a caller fills in. The Model Context Protocol, under the Agentic AI Foundation,
specifies that schema as JSON Schema 2020-12 and names `$ref` and `$defs` as allowed content.
The clients and model APIs that read it each accept only part of that language: one cannot
follow a reference, a grammar-constrained mode forbids recursion or demands every object
closed, a model API admits three keys at the root. Handed a construct it cannot read, a
client fails, or worse, quietly sends something else.

So a Nucleo server publishes each tool's one contract in several named spellings, called
dialects, and a client asks for the one its model stack reads. The set of argument documents
a tool accepts, and the results it returns, are the same in every dialect; only the spelling
of the schema changes.

A dialect is a promise to a stranger's client and is kept like one: it keeps its name and its
rules even when the internal serialization it is derived from
([Answers as Java objects](../../harness/schema/PACKAGE.md)) changes.

## Asking for a dialect

A request names its dialect with the optional `_meta` key `ai.redouble/dialect`, whose value
is the dialect's name from the table below. A client whose only configurable surface is its
URL adds a `dialect` query parameter instead, which the host forwards into the same slot; the
request's own naming wins over the parameter. Omitting both means `canonical`, and a name that
is no dialect is refused with the accepted names. The server's answer to `initialize` names
the key and every accepted value, so a client needs no documentation to find them.

One endpoint serves every dialect, because the dialect is a parameter of the request. Nothing
about a deployment decides which dialects exist: a host wires one transport and gets all of
them.

## The dialects

| Dialect | Named by | Rule set and its source |
|---|---|---|
| CANONICAL | `canonical`, or omitted | identity; the specification's own form |
| FLAT | `flat` | no reference anywhere, cycles broken to a titled shapeless object, harness keywords removed; for clients that cannot resolve a reference (Claude Desktop, Claude Code, Codex, Copilot CLI, Cursor as of 2026-09) |
| OPENAI_STRICT | `openai_strict` | OpenAI structured-outputs supported-schemas section: closed objects, every property required and optional ones nullable, references and recursion kept, its keyword list |
| ANTHROPIC_STRICT | `anthropic_strict` | Anthropic structured-outputs limitations: closed objects, no recursion, no numeric or length constraints, listed formats only |
| BEDROCK_STRICT | `bedrock_strict` | Bedrock structured-output page, the same rule set published separately |
| NOVA | `nova` | Amazon Nova tool-use page: root carries only `type`, `properties`, `required`; no references; no titles |
| GEMINI | `gemini` | Vertex function-calling schema attributes: `ref`/`defs` without the dollar sign, its attribute list, `format` for date-time only |

Each rule set follows the vendor's published subset. When a vendor moves, the dialect's rules
move with it and the dialect keeps its name.

## A dialect narrows the description and keeps the acceptance

The same documents are accepted in every dialect. What a dialect changes is how much of
that one rule it manages to spell, and every dialect spells less than the canonical form: FLAT
and the strict subsets and NOVA cannot express recursion, the strict subsets and GEMINI
cannot express an integer bound, GEMINI cannot express the `date` format or closure at all,
NOVA cannot express closure at its root.

Left alone that would be a trap: a caller obeys the schema it fetched and is refused for a
rule that schema had no keyword for. So every dialect writes the rules it is about to stop
spelling into the property's `description`, which is the one keyword none of them narrow.
An integer whose bounds are dropped gains "Accepted range: -2147483648 to 2147483647.", a
temporal string whose format is dropped gains "Must be an ISO-8601 date.", an object whose
closure is dropped gains "No other properties are accepted.", and the shapeless object a
broken cycle leaves behind gains "Repeats the structure of Node, to any depth." The
author's own description comes first and is never replaced.

One rule cannot be carried anywhere: closure at NOVA's root, because that root admits
`type`, `properties` and `required` and nothing else, not even a description. A caller in
that dialect learns it from the refusal.

One dialect changes what a caller sends as well as what it reads. OPENAI_STRICT marks every
property required and lets an optional one be null, so a model behind it sends an explicit
null where a canonical caller would leave the key out. The server reads such a null as the
omission it stands for, and only on a property the canonical schema declares optional.

Every call is then judged against the canonical schema, whichever spelling the caller read,
so a dialect never widens what is accepted.

## How it works inside

How a call is judged twice, how the dialects are built from the canonical schema, the shared
inliner, and the tests that pin every dialect are in
[Inside the MCP server](MCP_SERVER_INTERNALS.md), for those working on the runtime itself.
