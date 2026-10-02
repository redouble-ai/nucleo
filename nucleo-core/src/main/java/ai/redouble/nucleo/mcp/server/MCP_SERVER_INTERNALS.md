# Inside the MCP server

This page is for people working on the runtime itself. How to offer tools to outside MCP
clients is in [Serving tools over MCP](PACKAGE.md), and what the schema dialects are for is in
[Schema dialects](SERIAL.md).

## Exposable is not exposed

| Layer | Owner | Mechanism |
|---|---|---|
| Exposable shape | tool author | `@MCP` + `@ToolName` on the tool class |
| Catalog | `McpToolCatalog` | scan of package prefixes for `@MCP` tools, plus explicit providers; refuses at construction what it could not publish |
| Admission | host `McpAccessPolicy` | `admits(consumer, provider)`, decided per request |
| Listing | `McpTransportInterposer` | the same predicate as admission, so a consumer sees exactly what it may call |

The catalog fails fast on an `@MCP` class without `@ToolName`, on two classes claiming
one name, on a provider whose input schema could not be generated, and (at server start)
on an empty exposure set. The same class arriving through a scan and explicitly is one
entry.

## Classes

| Class | Role |
|---|---|
| `McpConsumer` | the resolved principal: its name (what every served job runs as) and its transitive group names (what a policy may grant by) |
| `McpConsumerResolver` | host-supplied: the `McpConsumer` behind a request, from the transport context; null or throw = unauthenticated |
| `McpAccessPolicy` | host-supplied: may this consumer use this tool, judged by its name or any of its groups; a throw is a refusal |
| `StaticGrantsAccessPolicy` | the simple implementation of that: grants by consumer or group name from configuration, validated against the catalog at startup |
| `McpToolCatalog` | the exposable set, by name; a miss is null, never an error |
| `McpSchemaPublisher` | `ToolProvider` to `McpSchema.Tool`; owns the outbound boundary rule, the input and output schemas, the weight `_meta`, and the `ARTIFACT_REFS` constant |
| `McpBoundaryJson` | the strict JSON reader a host gives its transport; refusals only the decoder can make |
| `McpInputGate` | strict admission of arguments against the published schema, composing every refusal from that schema |
| `McpErrorText` | what a failure may say to another process: never the caller's own text |
| `McpTransportInterposer` | fail-closed decorator around the host's stateless transport; per-consumer gate for listing and calling, and the input gate ahead of the SDK |
| `McpToolServer` | assembly and lifecycle: setters, `start()`, `close()`; the per-tool call handler |
| `SchemaDialect` | the spellings one tool contract is published in, selected per request |
| `SchemaRewrites` | the schema rewrites the dialects compose, and the inliner the client's normalizer shares |

## The gate

The SDK server lists every registered tool unconditionally and has no per-request hook,
so admission is enforced one seam below it: the interposer wraps the handler the SDK
installs on the transport and judges every JSON-RPC request first.

- Request methods are whitelisted (`initialize`, `ping`, `tools/list`, `tools/call`);
  anything else is answered with the SDK's own method-not-found shape without being
  delegated. Notifications delegate.
- `tools/list` is delegated and its result filtered to admitted tools; an
  unauthenticated request, or a result of any shape other than the SDK's, lists nothing.
- `tools/call` is delegated only for an admitted consumer and tool. Every other case,
  including an unknown name, an unauthenticated request, or params in an unreadable
  shape (a name that is not a string, a `_meta` that is not an object), is answered with
  one constant response: grants cannot be probed, and the name the caller asked for is
  not repeated back. The `_meta` shape is judged here because the SDK converts params
  before any handler runs and its parser quotes what it could not read. The delegated request carries the resolved consumer on its
  transport context under `McpTransportInterposer.CONSUMER_KEY`; the call handler reads
  it and never resolves again.

## The call

A served tool is the root of its own flow. Its arguments have already been admitted by
`McpInputGate` at the interposer, so the handler parses them (a null `arguments` is an
empty object) rather than judging them, creates the tool under
`Job.workflow(consumer, "mcp")`, sets its input, marks a thinker as invoked-as-tool, hands a
registry-aware tool a fresh artifact registry (the served tool is a root, so the handler
provisions what a dispatching thinker otherwise would; the registry lives for the call), and
submits it through the public dispatcher door from the request thread. The consumer
name is the principal every auth and admission guardrail on the tool judges; input and
output guardrails run at dispatch; a scoped root orchestrator seals its own scope for
its descendants. The handler enforces nothing itself.

The handler runs on the transport's request thread (`immediateExecution`): the servlet
transport blocks on it anyway, so the container's thread pool is the bound at the door
and the dispatcher's admission is the bound on the work; the server itself neither
queues nor refuses a call. A call that outlives the required timeout has its
whole workflow cancelled (`JobDispatcher.cancelWorkflow`), so sub-agents die with the
root; cancellation is cooperative, so a leaf mid-call finishes that call.

Every failure leaving the handler body is one error result carrying the text
`McpErrorText` composes for a foreign consumer, with `meta.correctable` telling the caller whether
a corrected input may succeed; a failure without LLM-readable text is wrapped as a
`SystemException` first. Nothing reaches the SDK's raw exception mapping.

Results ship full-form: the whole output object, a thinker's reasoning fields included,
is serialized by `NucleoJsonSerializer` as one text block; a null result is the JSON
literal `null`. There is no size cap, and that is the contract rather than an omission:
the wire carries producer output, and summarizing it would be this process deciding a
consumer's context budget for it. What the consumer's harness does with the payload is
the consumer's policy.

Before serializing, the handler mints refs on every artifact reachable from the result
(`ArtifactRegistry.indexReachableFrom`). A ref is minted only at registration, and
nothing registers a served result, so without this pass artifacts would serialize with a
null ref and arrive as anonymous data. The registry is throwaway; what survives is the
ref stamped on each artifact, which carries its `@TypeAlias` and is what lets a redouble
consumer rebuild the typed object (see `MCPArtifactRehydrator` in the client package).

## The boundary

The generated input schema (`ClassToolProvider.schemaJson()`) is standard JSON Schema
with a proper `required` array, `items` on every array, `format` on every temporal field,
and `minimum`/`maximum` on every scalar integer field, from the width of the field it feeds
(an integer inside an array publishes its type and no bounds, and the gate judges it by type
alone). A map field publishes as an object with no declared properties and
`additionalProperties` stating its value shape - a scalar, an enum's constants, a nested
definition or its reference, or `true` for a map to Object - and the gate judges every entry
against that shape under the entry's own key; an `Object` field publishes no type and admits
any JSON. The publisher declares every other object closed (`additionalProperties: false`,
root and nested), because the gate refuses an undeclared property at every depth and the
document a consumer reads must be the criterion its call is judged by; a map, whose keys are
the caller's, keeps the value shape it was published with. It removes the one field that
carries references into this process's artifact registry, `ThinkerInput.artifactRefs`,
published as `artifact_refs`; a caller that sends the key anyway is refused by the gate as
an undeclared property. A test pins the constant to the generator's own key. `query` and
`depth` stay: they are the thinker's contract with any caller.

Artifact refs go the other way on the OUTPUT schema and stay: there they are the identity
being shipped, not a pointer a foreign caller is asked to resolve.

## Two rules for everything arriving from outside

**A payload whose shape is unexpected is refused.** The published schema is the acceptance
criterion and `McpInputGate` accepts exactly that: no undeclared property at any depth, no
missing or null required value, no coerced type (`"7"` is not an integer, `"yes"` is not a
boolean, and a null on an optional property is not its declared type either; absence is
spelled by omitting the key), no string outside a declared `enum`, no temporal string that is not the ISO-8601
form its `format` publishes, no integer outside the `minimum`/`maximum` its field's width
publishes, no number no double can represent, and no string carrying a C0 control
character other than tab, newline and carriage return or an unpaired UTF-16 surrogate:
neither survives being written to a UTF-8 stream or a text column, so they are refused
where they arrive rather than corrupting something later.

The gate judges what the document declares and nothing more. A property published without
a `type` accepts any JSON and is carried: refusing it would refuse what the contract said
was allowed, and typing it in the gate would be a rule no consumer was told. The generator
types every property it emits, so a served tool reaches that only by publishing a
deliberately open property.

What the gate judges is the PUBLISHED schema, `McpSchemaPublisher.canonicalInputSchema`,
not the generator's: the boundary removes `artifact_refs` from what it publishes, and a
gate reading the generator's tree would accept a property no dialect published. One document,
one acceptance criterion.

A recursive input type is published once under `$defs` and referred to from every later
occurrence. The gate follows each `$ref` to its definition before judging the value under
it, so the same rules hold at every depth of the tree; a node with only a reference has no
`type`, and a gate that stopped there would admit anything below it. A reference the
published schema cannot resolve is a defect in our own document and throws as one.

Every one of those is read from the published contract or from a physical width; none is a
number chosen in the gate. Size, nesting depth and string length are not checked here at
all: Jackson's `StreamReadConstraints` bounds nesting depth, number length and string
length in the decoder before the gate sees a tree, and re-checking would only add a second,
tighter number to keep in step with the first. Those are Jackson's defaults, which
`McpBoundaryJsonTest` pins so a library that changes its mind fails the build rather than
the boundary. A limit belongs here when it says what we accept or what a field can
physically hold, and nowhere else.
In process the serializer is deliberately lenient because its input comes from our own
model and a model should correct itself; none of that reasoning survives a process
boundary, where silently ignoring half of what a consumer sent is how a caller ends up
believing a filter applied when it did not.

The gate runs in the interposer, ahead of the SDK's own schema validation, for the same
reason tool admission does: the SDK's refusal quotes the payload and carries none of our
correctable marker, so judging first is what makes the refusal one this process composed.
Content is not shape - a string field declares a string, and a query containing `../` or
`DROP TABLE` is accepted as text. Refusing on content is the guardrail layer's business.

**Nothing a caller sent comes back.** Every refusal is composed from this process's own
schema: a declared parameter name, a declared type, a limit, the accepted enum values. An
undeclared property is reported as a count against the accepted names, never by the name
the caller invented. `McpErrorText` renders every failure the boundary did not author:
`ClassToolProvider` logs the parser's complaint and names only the field path (Jackson's
own reference chain, which is our field names) and its declared type, and a
`ResourceNotFoundException` names its resource type without the identifier it was given. The one refusal rendered whole is
the gate's, because the gate composes it from our schema. `McpTransportInterposer.unknownTool`
is constant, so a tool that does not exist and one the consumer may not call are a single
answer and neither repeats the name that was asked for.

The rule is verified rather than trusted: `McpHostileInputTest` replays a canary-tagged
corpus of malformed and hostile payloads through the real SDK server and asserts no canary
appears in any response.

## The decoder

Several refusals can only be made before anything is parsed, and the gate never sees bytes.
`McpBoundaryJson.strictMapper()` is what a host gives its transport: duplicate keys,
trailing bytes after the document, comments, trailing commas, single quotes, unquoted
names, leading zeros, raw control characters, `NaN` and `Infinity` are all refused there.
Duplicate keys are the reason it exists - Jackson's default keeps the last silently, so
`{"amount":1,"amount":9999}` would reach the gate as one well-formed field and pass every
check it has. The host wires it (see the hosting section of the guide); `McpBoundaryJsonTest`
pins it.

## The output schema, and what declaring one commits us to

`McpSchemaPublisher` publishes `outputSchema` and the handler fills `structuredContent`,
so a consumer knows what it is receiving instead of parsing a text block. The SDK enforces
the pairing exactly: a non-error result must carry structured content when a schema is
declared, must not carry it when none is, and the content is validated against the schema.
Two rules follow, both in `outputSchemaOf`, which the handler consults so the declaration
and the response cannot disagree.

- **Object-shaped outputs only.** MCP structured content is an object. The schema generator
  writes `"type": "object"` for whatever it is handed, so `ClassToolProvider` asks it only
  about composites (`NucleoJsonSerializer.isComposite`); a tool answering a bare string or
  number publishes no output schema and sends no structured content.
- **Only what the generator can describe truthfully.** The schema walker has no generic
  resolution, so a `ListArtifact<T>` field's schema collapses to a string while the wire
  ships the whole list, and every successful result would then be refused against the
  published document. `ClassToolProvider` publishes no output schema for an output carrying
  a `ListArtifact` field anywhere in it; the result ships as text content alone.
- **No `required`.** On an input it means the caller must supply the field. On an output
  `@LLMRequired` is a directive to the model filling the POJO, never a promise to a
  consumer - publishing it would hand the validator our own prompt guidance to hold us to.

A tool that declares an object output and returns null violates the contract it published,
and the SDK turns that into an error result naming the omission.

`Tool._meta` carries the tool's `@ToolWeight` under `ai.redouble/weight` (type, level, min,
max). Nothing else in the MCP surface conveys cost, so without it a consumer cannot tell a
seconds-long API wrapper from an agent that will run for minutes.

JSON: SDK objects use the SDK's mapper (`McpJsonDefaults.getMapper()`, resolved through
the `mcp-json-jackson2` bridge); ours use `NucleoJsonSerializer`.

## Dialects

One tool contract, published in several spellings, chosen per request. The canonical one is
JSON Schema 2020-12 as generated, the specification's form; every other `SchemaDialect`
(`ai.redouble.nucleo.mcp.server`) is a narrowing for a consumer that accepts less.
Arguments and results are identical in every dialect, and every call is judged against the
canonical schema whichever spelling the caller read.

The dialect is an optional parameter, never a property of the endpoint: the `_meta` key
`ai.redouble/dialect` on any request, or the same key on the transport context for whatever
a host forwards (typically a `dialect` query parameter), with the request's own
naming winning. Omitted means canonical; a value that names no dialect is refused with the
accepted names, which are ours. So a host wires one transport and serves every dialect, and
no deployment decides which exist - adding one is an entry in the enum.

`start()` builds one catalog and one SDK server, whose registry holds
the canonical rendering. A `tools/list` that named another dialect is republished per
admitted tool by the interposer, through `McpSchemaPublisher.publish(provider, dialect)`.
`initialize` carries instructions naming the key and the accepted values, so a client needs
no documentation to find them.

Every call is judged twice, both times by `McpInputGate` and both times in words this
process composed.

First the document as it arrived, against the schema the request's dialect publishes. A
caller that obeyed the document it fetched passes here, and one that did not is refused by
us rather than by a validator whose message we did not write. This is why the SDK's own input
validation is switched off (`validateToolInputs(false)`): a second judge behind ours would
answer in its own words, quoting the caller and carrying none of the correctable marker.

Then the same arguments in canonical form, against the canonical schema, which carries
every constraint that spelling could not say: an integer bound Gemini has no keyword for, a
`date` format it drops, closure Nova's root cannot state. A dialect narrows the
description; it never widens what is accepted.

Between the two passes sits `SchemaDialect.arguments`, the inverse of whatever that
spelling changed about the documents a caller sends. Only OPENAI_STRICT changes them, an
omitted optional arriving as an explicit null; every other dialect's inverse is the
identity. It removes a null only where the canonical schema declares an optional property,
so a null on a required property reaches the second pass as a missing one and a null under
an undeclared name as an undeclared property. The dialect is stamped on the transport
context beside the consumer, and the handler applies the same inverse once more before
parsing, so nothing is ever parsed from a non-canonical document.

Required means the key is present. Whether the value it carries is acceptable belongs to
the type check, which is the only place that knows whether a schema admits a null there:
the strict spellings mark every property required and let an optional one be its own type
or null, so a rule that read a present null as an absent key would refuse the very
documents those dialects ask their callers to send.

### The dialect enum

`SchemaDialect` is the enum. Each value carries the name a request selects it by
(`wireName()`, the enum name lowercased) and three pure functions over
a copy: `inputSchema(canonical)`, `outputSchema(canonical)` and
`arguments(wire, canonicalInputSchema)`. Strict dialects apply their model-facing rules
(all required, nullable unions) to the input schema only, because a model never generates
a result; output schemas get the vocabulary narrowing alone. `arguments` is the inverse of
whatever a dialect changed about the documents a caller sends: only OPENAI_STRICT changes
them (an omitted optional becomes an explicit null), so only its `arguments` does anything,
and the gate never sees a non-canonical document. The inverse follows the canonical schema
and removes a null exactly where that schema declares an optional property, through
`properties`, `items` and `$ref`. A null on a required property stays and is a missing
property to the gate; a null under a name the schema does not declare stays and is an
undeclared property to the gate. The inverse undoes the spelling and never makes a
judgement the gate owns.

`SchemaDialectTest` pins each dialect against the rules in [Schema dialects](SERIAL.md).
When a vendor moves, the test moves with it and the dialect keeps its name.

Three tests in `McpDialectHostileInputTest` guard the set itself, all driven from
`values()`: every dialect the enum declares is answerable through the host's one transport,
no dialect renders every tool in the catalog exactly as canonical does (which would be a
stub), and the per-dialect rules check is a switch expression, so an added dialect does not
compile until its rules are written.

### The shared inliner

`SchemaRewrites` holds the rewrites the dialects compose, and one of them is public:
`inlineForeignRefs`, which the inbound normalizer (`ai.redouble.nucleo.mcp.MCPSchemaNormalizer`)
uses on schemas other servers publish. Two policies share one walk. A canonical schema, one
this process generated, throws on a reference it cannot resolve, because that is a defect
of ours. A foreign schema collapses such a reference to a bare object, because a tool whose
schema we cannot fully read is still worth offering. Both keep a use-site keyword over the
definition's, per 2020-12. A cycle is the one shape that cannot be inlined: a dialect that
forbids recursion breaks it to a titled shapeless object, and the foreign policy keeps the
reference with its definition under `$defs`.

## Tests

`McpToolCatalogTest`, `McpToolServerTest` (the real SDK server behind the real
interposer, tools run by the real dispatcher, driven through a captured transport),
with fixtures under `fixtures`, `brokenfixtures`, and `collisionfixtures`.

The boundary's own campaigns: `McpHostileInputTest` drives the corpus of malformed and
hostile-content payloads in the canonical dialect, `McpDialectHostileInputTest` drives
the same corpus through every dialect plus the strict spelling's own rules and the three
guarantees on the dialect set itself, `McpBoundaryJsonTest` is the raw tier for the
decoder, and `McpInputGateReferenceTest`
judges a recursive input below its root. Every payload carries a canary in its value and,
where the hostile element is a key, a second one in the name; the echo rule is asserted on
the whole JSON-RPC envelope of every response, refusal or result.
