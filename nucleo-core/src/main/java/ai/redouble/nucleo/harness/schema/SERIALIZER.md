# Inside the serializer

For people working on the runtime itself. How an answer class is described to a model and
read back is in [PACKAGE.md](PACKAGE.md); this page is how the package does it: one
serializer for both directions, the two renderings of a schema, and the rules the mapper
holds.

A POJO is reflected once into a schema the model is instructed to fill, and the reply is
read back as the object the code declared. Because one implementation does both, the
contract the model saw is exactly the contract the answer is checked against.
`PojoResponseHandler` (in `harness.conversation`) reflects a class into the schema model
once; the model then renders in two forms.

| Class | Role |
|---|---|
| `PojoDefinition` | a class: its name, description, and ordered fields; a class with no fields is a definition with an empty field map |
| `FieldDescriptor` | one field: JSON name, description, type label, nested definition for composites, the enum constants an enum-typed field or collection accepts (in the serializer's wire form), the declared examples, whether it is required, and its `x-nucleo-*` keywords |
| `NucleoSchemaKeywords` | the `x-nucleo-*` vocabulary: the keyword names, and the strip that takes them back out |
| `NucleoJsonSerializer` | the one mapper: writing in three modes, reading a model's raw answer, reading clean JSON, the tree and conversion entry points |
| `TypeAlias`, `TypeAliasRegistry`, `MissingTypeAliasException` | the hierarchical name an artifact class carries in its references, and the registry that resolves it both ways |
| `LLMDescription`, `LLMRequired`, `LLMExample`, `LLMSummarizable`, `SummarySize`, `LLMContextIgnore` | the annotations |
| `Reasoning` and its four implementations, `ReasonablePojo` and its two bindings | the reasoning a response carries beside its answer |

Required-ness is a flag on the descriptor, not a substring of its description. The
description still carries a `(REQUIRED)` token for the model to read, but nothing parses
it back: a description that merely contains those characters produces no required field.

## What each annotation does inside

| Annotation | On | In the schema | In the serializer |
|---|---|---|---|
| `@LLMDescription` | class, field | the description; a class without one reads `Schema of <Name>`, a field without one `Field <name>` | nothing |
| `@LLMRequired` | field | `@required` / the `required` array, and the `(REQUIRED)` token in the notation's prose | nothing at read; `PojoResponseHandler.validateRequiredFields` refuses null and a blank String, and accepts an empty collection as "nothing found" |
| `@LLMExample` | field | `@examples` / `examples`; a value that parses as an integer publishes as a number | nothing |
| `@LLMSummarizable` | field of an artifact | nothing: no keyword tells a consumer to make a summary | in the summarized modes, on a bean that is an `Artifact`, a value past `threshold` is written as its summary, decided in one place, below; a field without a hint is warned about once per process |
| `@LLMContextIgnore` | field | stays in the schema so a caller can populate it | omitted from every write a model reads (`writeSummarized`, `writeSummarizedWithRefs`, `modelFacingFields`) and kept in `write` and `writeStoredWithRefs` |
| `@TypeAlias` | artifact class | the `x-nucleo-type-alias` of fields typed with the class | the alias its references carry, below |

### The summary of a field

`SummarizablePropertyWriter` is the one place a summary is decided. It works on the value
as the serializer would write it without the annotation: a `String` as it is, any other
value as its compact JSON, which is also what `threshold` measures. For a value past the
threshold it takes, in this order:

1. the summary already kept on the artifact (`ArtifactSummaries`, from
   `Artifact.getSummaries()`), under the field's name;
2. `staticSummary`, written as `[SUMMARY: N chars] <phrase>`, and kept;
3. what the field's own `FieldSummarizer` returns, kept; when the class throws, returns
   null or cannot be made, the text is cut and nothing is kept;
4. the cut, kept, when `llmSafe` is false or the text is longer than
   `NucleoJsonSerializer.MODEL_SUMMARY_MAX_CHARS` (200,000);
5. a model's summary, kept, only in `NucleoJsonSerializer.makeSummaries(artifact, model)`
   and only while the thread is not interrupted;
6. otherwise the cut for this write, with nothing kept.

The cut is `TruncatingSummarizer`: the head of the text within the `size`'s budget (~200 /
~500 / ~1200 characters), marked `[SUMMARY: N chars]` with the full length.

`makeSummaries` is called for every artifact of a conversation by
`ConversationContext.summarizeArtifacts`, which the `LLMCall` constructor calls with an
`LLMSummarizer` on the thread of the thinker or doer that builds the call, so the
summarization jobs are that job's children. `ArtifactSummaries.make` is single-flight: of
several threads that need one summary, one asks for it and the rest wait for its result; a
waiter whose owner failed gets nothing and cuts. An interrupted thread has its flag set
again, asks for no summary and cuts, keeping nothing.

A pre-summarized tree, `@LLMSummarizable(value = "...", preSummarized = true)`, is for a
`Map<String,Object>` / `List<Object>` field that keeps its shape (`MCPArtifact.data` is the
case). The value is walked, and each text leaf past the threshold goes through the same
decision under the key `<field>/<JSON pointer of the leaf>`, such as `data/results/0/text`.

`size()` (a `SummarySize`: `BRIEF` / `SHORT` / `PARAGRAPHS`) sets the target for both the
summarization job's prompt phrasing and the cut's character budget. The enum is rendered as
a prose phrase in prompts ("a short summary of about a paragraph"), never as a number - a
precise count in the prompt invites small-model verification spirals. The job-backed
`LLMSummarizer` lives in `tools.builtin` beside the `SummarizationTool` it wraps, because
spawning summarization jobs is tooling, not schema vocabulary; it throws
`SummarizationFailedException` when the job produces no summary.

### Where the annotation may stand

`SummarizablePlacement.check()`, the first thing `JobDispatcher.start()` does, scans the
class files of the context class loader's roots and of the Java class path for fields that
carry the annotation, loads each declaring class without initializing it, and throws
`SummarizableOutsideArtifactException` naming every field whose declaring class is no
`Artifact`. A class loader that passed is not scanned again. The compile-time check is
`SummarizableFieldProcessor` in `nucleo-processor`, which depends on nothing of the runtime
and knows the annotation and the interface by name; the Quarkus build's is
`NucleoQuarkusProcessor.summarizablePlacementCheck`, over the Jandex index.

## Two renderings

- `toLLMSchema()`: the framework's `@`-notation (`@type`, `@required`, `@description`,
  `@examples`, `@values`, `@fields`), the form the response-format legend explains to a
  model. An enum-typed field is `@type` `enum` (a collection, `array of enum`) with its
  accepted constants under `@values`, so authors never spell the constants out in the
  description.
- `toJsonSchema()`: standard JSON Schema, the form tool definitions and external
  publication use. Required-ness is the `required` array, an enum-typed field is a
  string constrained by `enum` (a collection of enums constrains its `items`), examples
  are the `examples` keyword, a map is an object with `additionalProperties` stating its
  value shape (`true` for a map to Object), an `Object` field is an untyped value, a date,
  a date-time or a time of day is a string with the standard `format`, an integer or long carries its
  width as `minimum` and `maximum`, a collection of scalars states its `items`, and the
  prose markers of the `@`-notation never appear. `toJsonSchema(Map)` replaces named
  fields' descriptions with the caller's text, for a schema whose shared parameters are
  described once elsewhere.

Both render from the same descriptor data; neither is derived from the other's text. In
both, the `(REQUIRED)` token and the `[Examples: ...]` suffix leave the description: they
are structure, rendered as `@required` / `required` and `@examples` / `examples`.

## Every object is named

Every object node of the JSON Schema rendering carries `title`: the type's simple class
name, the same name the `@`-notation prints as `@type`. It is the keyword JSON Schema
reserves for a type's name, so a nested type is never an anonymous object to a reader, and
the name survives any client that inlines references. The `$defs` key of a recursive type
is that same name.

Models echo that name in two ways the parse tolerates (`NucleoJsonSerializer.parseLLMResponse`):
an answer written in the schema's own shape, values under `@fields` beside `@type`, is
hoisted into the instance it encodes; an answer wrapped under the type's name as its one
key, `{"ThinkingResponse": {...}}`, is unwrapped to the object beneath. Either, left as
written, maps to an all-null instance and reads as a silent decline.

Because title and definition key are both the simple name, two different classes sharing
one simple name inside one schema would be indistinguishable to any reader. The generator
refuses to describe such a schema and names both classes; the fix is a rename. For a tool,
a schema that fails to generate means no schema and no MCP exposure, and
`ClassToolProvider` reports the cause once at registration.

## A cyclic type graph

A type may contain itself - a crawled page whose `subpages` are a list of crawled pages is
the honest shape of the thing - and a walker that inlines every composite it meets never
terminates on one. The walk therefore carries the types currently ON THE PATH, and a
field that comes back to one becomes a reference instead of another copy: `$ref` into a
`$defs` section on the root for JSON Schema (an array of the type is an array whose items
are that reference), the type's bare name for the `@`-notation, or `array of <name>` for a
collection of it, which has already described it in full at the occurrence containing this
one. A reference field keeps its own description, examples and `x-nucleo-*` keywords beside
the reference.

Keyed on the path rather than on everything seen, so two sibling fields of one acyclic type
are still described in full and an ordinary schema is unchanged; only a genuine cycle
becomes a reference. A schema with no cycle carries no `$defs` at all, and `$defs` is
emitted only at the outermost render - a self-referential type is its own referenced
definition, so an entry that emitted its own `$defs` would descend forever.

This is load-bearing well beyond schemas. The walk runs in `ClassToolProvider`'s
constructor, which runs at tool registration, which runs inside a thinker's constructor, so
the walk terminating on every type graph is what lets an agent whose palette holds such a
tool be built at all. `SchemaRenderingTest` pins both renderings of a cyclic type.

## A schema has two audiences

Standard keywords - `type`, `description`, `required`, `enum`, `examples` - are what a
MODEL reads in a tool definition. The `x-nucleo-*` keywords are what a consuming HARNESS
reads, and they carry facts about the data that a harness needs and cannot derive:

| Keyword | Carries | Why a consumer cannot derive it |
|---|---|---|
| `x-nucleo-context-ignore` | `@LLMContextIgnore` | the field is in the payload and belongs out of a model's context |
| `x-nucleo-type-alias` | the `@TypeAlias` of an artifact-typed field, or of the elements of an artifact-typed collection or array | what the field's refs point at, when no ref is present to read |

In process a harness reads the annotations off the class. Across an MCP boundary the
concrete class may not resolve, and then the schema is the only source. The keywords say
what the data IS; what a consumer does about it stays the consumer's policy.

JSON Schema ignores unknown keywords, so they are inert for anyone who does not know them,
and `NucleoSchemaKeywords.stripFrom` removes them again. On the path to a model -
`AbstractThinker.buildToolDefinitionBlocks`, the one place a schema becomes model-facing
for both the text form and a provider's native tools API - the schema goes through
`NucleoSchemaKeywords.forModel`, which first reduces every field marked
`x-nucleo-type-alias` to an object whose one property is the artifact's `artifact_ref`, an
array of artifacts in its items, and then strips: a model refers to an artifact and never
writes one ([Artifacts](../artifacts/PACKAGE.md)). That is why the prefix has to be
stable: it is what the strip keys on. Both answer null for null and an empty string
for an empty one, so a provider with no schema passes through untouched.

## The mapper

`NucleoJsonSerializer` holds the one `ObjectMapper` of the runtime; nothing else creates
one. Its configuration is the contract every reader and writer shares:

- properties travel in snake_case, in both directions;
- a null field is omitted on write; a bean with nothing to say writes `{}`;
- a property the class does not declare is ignored on read, and a null for a primitive
  leaves the primitive's default;
- the syntax a model produces is accepted on read: comments, trailing commas, single
  quotes, unquoted names, any backslash escape, `NaN` and the infinities;
- `java.time` types serialize through the JSR-310 module, and `Prompt` through its own
  module.

The write modes:

- `write` / `writeCompact`: every field, no transformation. The compact form backs the
  observability columns, which are parsed before they are read.
- `writeSummarized` / `writeSummarizedCompact`: an artifact written with its fields, its
  `@LLMSummarizable` fields summarized as above and `@LLMContextIgnore` fields omitted; a
  `ListArtifact` rendered as a bounded digest. The form the registry section shows.
- `modelFacingFields(artifact)`: the fields of one artifact as a JSON object, whole, with
  `@LLMContextIgnore` fields omitted and an artifact inside written as its reference. What
  the artifact tools read and search; `modelFacingFieldNames(class)` lists the names.
- `writeStoredWithRefs`, with an `ArtifactRegistry`: every field whole, `@LLMContextIgnore`
  included, with each artifact replaced by its reference and registered. The form a prompt
  object is persisted in; the artifacts themselves are stored by the registry.
- `writeSummarizedWithRefs`, with an `ArtifactRegistry`: every artifact
  replaced by `{"@ref": "..."}` and registered, and `@LLMContextIgnore` fields omitted. The
  form that goes into a prompt. Registering an artifact mints a reference for every artifact
  reachable inside it and indexes each as reachable, resolvable through the registry without
  being a top-level entry. An artifact that reaches this mode with no reference and no
  registry to mint one is a framework fault: the write fails, with an `IllegalStateException`
  naming the artifact as the cause.

A serialization failure is a `RuntimeException` that describes the object's fields by
size, a collection by its count and a string by its length and a bounded prefix, never by
reproducing them: the object that could not be serialized is the one thing not to render
again.

Reading a model's raw answer, `parseLLMResponse`: the last top-level balanced `{...}` or
`[...]` span of the text is the answer, so reasoning before it, a markdown fence around it
and prose after it are dropped, and bracket-looking text inside strings does not confuse
the search (`extractJsonFromLLMResponse`); raw control characters inside strings are
escaped; typographic punctuation outside string values is rewritten to ASCII, so curly
quotes around a key or a value become the quotes the parser reads while what a model wrote
inside a value stays as written; the two schema echoes above are unwrapped; then the lenient
mapper reads it. A text with no balanced span is refused with an extraction failure; a span
that will not map is refused with a mapping failure that names the target.
`extractJsonWithProse` splits
the same text into the span and the trimmed prose around it, for a caller that keeps the
reasoning a model wrote beside its answer.

The coercions, applied wherever the mapper reads: a boolean from Y, YES, T, TRUE, 1 and
their negatives in any case, and from the numbers 0 and 1; an integer from a whole-number
float or its text, an empty string as null, a fraction refused; a `LocalDate` or
`LocalDateTime` from any shape `Temporals.parseLenient` reads or from a numeric array
(`[y, m, d]`, `[y, m, d, h, min, s, nano]`), a date-time contributing its date part to a
`LocalDate` field and a date alone reading as the start of that day for a `LocalDateTime`
field; an enum by exact constant name, then case
insensitively, then from an object whose first textual member names the constant; a field
declared as the `Artifact` interface from its reference. What a coercion cannot read is a
mapping failure that names the value, never a guess.

`parse` reads clean JSON with the same mapper; when the text does not parse as it is, it gets
one repair, raw control characters inside strings escaped and typographic punctuation outside
strings rewritten to ASCII, and refuses empty input; `readTree`,
`convert` and `valueToTree` are the tree and conversion entry points over the same mapper;
`isComposite` is the leaf test the schema walker and the runtime type recovery share: a
user-defined class is composite, a primitive, enum, array, collection, map, JDK scalar or
temporal, or anything under `java.*`, `javax.*` and `jakarta.*` is a leaf.

## Type aliases

An artifact class declares `@TypeAlias("link:cite:pubmed")`: colons are IS-A levels,
hyphens join words, and the alias is unique. The alias travels in every reference to an
instance (`«artifact:link:cite:pubmed~uuid»`) and is what resolves the concrete class on the
way back, so no `@type` field is needed in the JSON.

`TypeAliasRegistry` maps both ways. A class registers on the first `getAlias` that meets
it, or through `register`, or through `init(packages)`, which scans the named packages and
their subpackages, whatever classpath roots hold them, registers every annotated class and
refuses with `MissingTypeAliasException` when a concrete `Artifact` class in them carries no
annotation; `init` runs once per process and a later call is a no-op. One alias on two
classes is refused with `IllegalStateException`. `resolveAlias` is exact; `resolveAliasWithFallback` climbs the colon
hierarchy to the nearest registered parent, so a consumer that lacks the exact class still
gets its nearest known ancestor, and answers null past the root.

## Reasoning

The four shipped shapes render a user-friendly description from the parts the model filled
in: the three structured ones say in words when it filled none, and `SimpleReasoning`'s
description is its thought, or null when there is none. A response class extends
`ReasonablePojo<R>` to bind one, or the shipped `StringReasonablePojo` /
`ChainOfThoughtReasonablePojo`; the reasoning is then a described field of the schema the
model fills like any other.
