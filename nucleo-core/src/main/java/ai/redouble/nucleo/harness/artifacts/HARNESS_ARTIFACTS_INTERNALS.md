# Inside artifacts

This page is for people working on the runtime itself: how artifact references are kept
canonical, how the parent's artifacts are resolved, the registry's two tiers, how artifact
types are recovered from references, and the text-rendering classes. The guide for using
artifacts is [Artifacts: data the model cannot alter](PACKAGE.md).

---

## Resolution for the parent

The parent doesn't parse the DOI from text. It gets the `CitationArtifact` object with exact
data from the registry - the thinker (`SingleObjectiveThinker.resolveArtifacts` in
`tools/thinking`) resolves the refs through `normalizeToKey()` and populates the artifact set
for the parent.

## The one gate against a model-written artifact

The readers in this package rebuild an artifact from a payload, which is right for a payload
a tool or another process wrote and wrong for one a model wrote. The two are told apart by
where the payload is read, and a model's reply is read in one place: `ResponseCorrection`,
which every model-calling job goes through (`LLMCall` for a thinker's turn,
`AbstractModelDependentTool` for a one-call tool). After the parse and before required-field
validation it calls `ResponseHandler.heldArtifacts`, whose default hands the reply to
`ArtifactRegistry.held` with the conversation's registry.

`held` walks the reply the way the custody walk does (composite objects, lists, other
collections, maps, arrays; never a JSON tree, never into an artifact) and replaces every
`Artifact` it meets with `get(ref)`, checking the held object against the declared type of
the place where generics state one. It returns violations as text for the model, so they
join the required-field errors: a correction turn within `ResponseCorrection`'s budget, a
`ResponseValidationException` past it. `ThinkingResponseHandler` overrides the hook because a
turn's tool calls are answered one by one: a violation in a call's input becomes that call's
parse error, which `executeTools` returns as the call's result, and the tool never runs.

The schema side has two renderers and one rule. `PojoDefinition.modelWrittenFields()` reduces
an artifact's definition to its reference in the `@`-notation form, which is only ever read
by a model. The JSON Schema form stays canonical, content included, for a caller that sends
artifacts across a process boundary; `NucleoSchemaKeywords.forModel` reduces every node
marked `x-nucleo-type-alias` to its reference on the path to a model, in the same step that
strips the keywords. `ArtifactAuthorshipTest` pins both sides and the walk.

## Canonical reference format

The canonical format for all artifact references is `«artifact:type~uuid»` (with guillemet delimiters). This single format is used everywhere - HashMap keys, registry display, `@ref` placeholders, `artifactRefs` lists, and tool inputs.

`ArtifactRegistry.normalizeToKey()` is the single normalization function that maps any variant to canonical format. All entry points (registry operations, response handling, tool inputs) normalize through this function, and `ArtifactRegistry.refsMentionedIn()` is the one authority for recognizing a ref inside prose.

A parsed response's refs are canonical by construction: `ArtifactResponse`'s list setter is package-private (Jackson's door only - no outside caller can plant a variant spelling), the response handler runs `canonicalizeArtifactRefs` right after every parse (normalizing the declared refs and unioning the text mentions, deduplicated, declared order first), and programmatic additions go through `addArtifactRef`, which normalizes each ref.

## Key classes

| Component | Purpose |
|-----------|---------|
| `AbstractArtifact` | Base class with `artifactRef` field and type extraction |
| `LinkArtifact` | Parent for artifacts with `title` + `url` (WebPageArtifact, CitationArtifact, PatentArtifact, etc.) |
| `ListArtifact<T>` | Ordered homogeneous list of artifacts (its iterands) - the bulk-data carrier, with lineage fields (`derivedFromRef`, `workerToolName`, `instruction`). Prompt-form is a bounded digest (`artifact_ref`, `iterand_type`, `count`, head sample), never the recursive per-iterand rendering; full form on plain `write()` for persistence |
| `ArtifactRegistry` | Storage, reference generation, `normalizeToKey()` for canonical format. Registers against the `Artifact` interface (no `AbstractArtifact` requirement). Two tiers: top-level entries render and persist; the reachable tier (everything discovered by walking a registered artifact's object graph, plus explicit `indexReachable()` conveyances) is resolvable via `get()`/`contains()` and searchable via `getAllArtifactsIncludingReachable()`, but excluded from `getAllArtifacts()` so prompt rendering and persistence stay independent of data size |
| `ArtifactResponse<R>` | Response base with `artifactRefs` field (canonical format) for LLM to populate |
| `ArtifactRegistry.REF_FIELD` | The serialized name of an artifact's ref (`artifact_ref`) - the one literal every reader that rebuilds an artifact from its payload keys on |
| `ArtifactListDeserializer` / `ArtifactRefDeserializer` / `ArtifactMapDeserializer` | Type recovery from the ref: the alias inside `«artifact:alias~uuid»` names the concrete class (`TypeAliasRegistry.resolveAliasWithFallback` climbs to the nearest known ancestor). The list and interface-field readers FAIL on an unresolvable alias - a silently shortened list or a silently null field is worse than a refused read; the snapshot-map reader SKIPS one with a warning, because restoring a conversation must survive an artifact class removed since the snapshot was written |

`PatentArtifact` lives in `nucleo-ext-patent`.

The serialization that turns artifacts into refs and summarizes their large fields is the schema package's (`NucleoJsonSerializer`, `@LLMSummarizable`, the `Summarizer` strategies - [Answers as Java objects](../schema/PACKAGE.md) and [Inside the serializer](../schema/SERIALIZER.md)). An artifact's summaries are cached on it (`Artifact.cacheSummary()`, `SummarizedField`) so they are computed once and reused across serializations.

## Text rendering

| Class | Role |
|---|---|
| `ArtifactTextFormatter<T>` | Per-type formatter contract: pure function, artifact in, canonical text out |
| `TextFormatterRegistry` | Class-keyed registration, resolution up the class hierarchy, canonical-JSON fallback for unregistered types; `find(Class)` answers the nearest registered formatter or null, for a caller that renders differently when none is registered (a decision thinker's digest) |
| `ListArtifactTextFormatter` | Built-in: count + iterand-type header, then every iterand numbered in list order through its own formatter; nested lists recurse |
