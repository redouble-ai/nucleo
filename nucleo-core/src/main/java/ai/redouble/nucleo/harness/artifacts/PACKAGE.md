# Package: ai.redouble.nucleo.harness.artifacts

Agent work is a relay: one tool fetches a record, a model passes it to the next agent, that
agent quotes it to a third. If the record itself travels in the models' replies, every hop is
a chance to lose a digit - paraphrased, shortened, confidently wrong. An artifact is how
Nucleo removes that chance. It is a tool's result that the runtime keeps in a registry under a
short reference (it has nothing to do with Maven artifacts). The model reads the record in
full, reasons over it and decides where it goes next, but it never carries the record: the
data travels with the runtime, outside anything the model writes. Ten handoffs later, the
record is still exactly what the tool produced.

[Data the model cannot alter](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/artifacts/PACKAGE.md)
shows it end to end: a tool returns a customer's orders as artifacts, the agent answers with
the references of the delayed ones, and the caller gets the tool's own objects back. This page
explains what happens along the way and how to use it.

## The problem: models corrupt the data they pass on

Agent A retrieves a citation from PubMed:

```json
{"doi": "10.1038/s41586-024-07386-0", "authors": ["Smith, J.", "Chen, L."]}
```

Agent A passes it to agent B through B's context, and B has to include it in its output for
agent C. Every model on that path is a chance to change it:

- **Paraphrasing**: "Smith, J." becomes "John Smith" or just "Smith".
- **Invention**: the DOI "10.1038/s41586-024-07386-0" becomes "10.1038/s41586-024-07387-0",
  one digit off.
- **Reshaping**: the JSON is restructured and fields are renamed.
- **Shortening**: a long author list is cut.

A wrong DOI is worse than no DOI: it looks authoritative and points to nothing. The usual
defenses do not hold. Structured output still shows the model the data it can change;
telling the model to be careful does not work reliably; and an external store of ids adds
coordination infrastructure whose ids can still be confused.

## Two channels: one to reason, one to carry

Nucleo separates what the model needs in order to think from what is handed on.

- **To reason**, the model sees the full content of every artifact, so it can judge relevance,
  compare and summarize.
- **To carry**, the artifacts go from the registry to whoever receives the result. Nothing is
  parsed back out of the model's text.

The model's job is to **select** which artifacts matter, never to **transmit** them. It can
write prose that points at an artifact, and the data still comes from the registry: even if
the model writes "DOI: 10.1234/wrong" in its text, the caller receives the correct DOI in the
artifact.

## How it works

**1. A tool returns artifacts.** An artifact is a plain data class extending `AbstractArtifact`
and named with `@TypeAlias`. A tool puts artifacts in its output like any other value: as the
output itself, in a list, or nested in the fields of another class.

```java
CitationArtifact cite = new CitationArtifact();
cite.setDoi("10.1038/s41586-024-07386-0");
cite.setAuthors(List.of("Smith, J.", "Chen, L."));
```

**2. The thinker registers each artifact.** As the tool's result reaches the model, the thinker
registers every artifact it finds in the registry of its conversation, which gives each one a
reference, and replaces the artifact in the result with that reference:

```json
{"@ref": "«artifact:link:cite~a7f3b2»"}
```

Code that holds a registry registers an artifact itself with `register`, which returns the
reference:

```java
String ref = registry.register(cite);  // Returns "«artifact:link:cite~a7f3b2»"
```

**3. The registry is shown in full.** The conversation carries a section listing every
registered artifact under its reference, with long fields shown as summaries, so the model
reasons over the real content:

```
=== ARTIFACT REGISTRY ===
--- «artifact:link:cite~a7f3b2» ---
{
  "doi": "10.1038/s41586-024-07386-0",
  "authors": ["Smith, J.", "Chen, L."]
}
```

**4. The model selects.** Every agent's answer class extends `ThinkerOutput`, which gives the
answer an `artifact_refs` list. The model fills it with the references of the artifacts the
caller should receive, and references it mentions in its text count as selected too:

```json
{
  "summary": "Based on the CRISPR study «artifact:link:cite~a7f3b2»...",
  "artifact_refs": ["«artifact:link:cite~a7f3b2»"]
}
```

**5. The caller receives both.** The answer's own fields are the model's words; `getArtifacts()`
on the answer returns the artifacts it selected, the objects the tool built, looked up in the
registry when the agent finished. A reference that names no artifact in the registry is logged
and skipped.

## Passing artifacts between agents

An agent calling another agent hands it artifacts the same way. The caller's model lists the
references in the called agent's input, `artifactRefs` on `ThinkerInput`, and the called agent
starts with exactly those artifacts in its registry and nothing else of its caller's
([Tools, thinkers and doers](../../tools/PACKAGE.md)). The artifacts the called agent selects
for its answer reach its caller as objects, and the caller can select them again for its own
caller. However many agents stand between the tool and your code, the record your code gets is
the tool's.

This also keeps agents specialized: an agent that reasons over a record needs neither the tool
that fetched it nor that tool's permissions, because the runtime carried the data to it.

## References

A reference has the form `«artifact:type~id»`, with the guillemets: the artifact's
`@TypeAlias`, then a short random id. The delimiters make a reference stand out from the prose
around it, so it survives the model's context intact, and the type tells a reader, person or
model, what it points at without looking it up. The same form is used everywhere: in the
registry section, in the `{"@ref": ...}` placeholders, in `artifact_refs` lists and in tool
inputs that take a reference. A model that writes a reference without the guillemets or the
`artifact:` prefix is still understood: `ArtifactRegistry.normalizeToKey()` turns every
variant into the canonical form, and `ArtifactRegistry.refsMentionedIn()` finds the references
in a text.

## Defining an artifact

Extend `AbstractArtifact` and give the class a `@TypeAlias`; a class without one cannot be
registered and fails with `MissingTypeAliasException`. Describe each field with
`@LLMDescription`, as on an answer ([Answers as Java objects](../schema/PACKAGE.md)). An alias
spells the class's place in its family: `CitationArtifact` is `link:cite`, a kind of
`LinkArtifact`, `link`. A reader that does not know a subclass still rebuilds the nearest
ancestor it knows, which gives it a useful object.

Nucleo ships these shapes, plain data classes to use or extend:

| Class | Alias | Holds |
|-------|-------|-------|
| `LinkArtifact` | `link` | a `title` and a `url` |
| `WebPageArtifact` | `link:page` | a fetched page: description, content, source |
| `CitationArtifact` | `link:cite` | a publication: authors, year, journal, DOI, abstract |
| `CodeArtifact` | `code` | a piece of code with its language and file |
| `PersonArtifact` | `person` | a person with affiliation, role and ORCID |
| `ListArtifact<T>` | `list` | an ordered list of artifacts of one type, its iterands |

`ListArtifact` carries bulk data. The model sees a list as a short digest - its reference, the
type of its iterands, their count and the first few - however long it is, and every iterand is
registered under its own reference, so a tool can be handed any one of them. Build the list
completely before it is registered: its iterands are indexed at registration, and a list
changed afterwards leaves the index behind. A list also records where it came from:
`derivedFromRef`, `workerToolName` and `instruction`.

A field holding more than is worth showing a model in full - a page, a patent, a sequence - is
marked `@LLMSummarizable`, and the model sees a summary while the artifact keeps the whole
value ([Answers as Java objects](../schema/PACKAGE.md)). Summaries are computed once and cached
on the artifact. A model that needs the whole value asks for it with `get_artifact_field`, and
`search_artifact_content` searches the text of every artifact ([Artifact
tools](tools/PACKAGE.md)).

A registry belongs to one conversation and is not safe to share between threads.

## Artifacts as text

Code that turns artifacts into text - a stored answer, the text form of a referenced artifact
in a chat, an export - wants the same artifact to give the same text every time.
`TextFormatterRegistry` provides that. Register an `ArtifactTextFormatter` for your artifact
class, a function from the artifact to its text. The demo's decision agent registers one per
kind of item it shows its model:

```java
TextFormatterRegistry.register(Folder.class, folder -> "the folder " + name(folder.getPath()));
```

`TextFormatterRegistry.format(artifact)` renders any artifact through the formatter registered
for its class or its nearest ancestor, so a formatter for a base type covers its subtypes. A
type with no formatter falls back to its full JSON, which is deterministic but not pretty:
register a formatter for any type a person will read. `ListArtifact` has one built in, which
writes the count and the iterand type, then every iterand numbered in order through its own
formatter. The registry promises determinism and resolution by type and nothing else: where
the text goes is the caller's choice.

A formatter must be a pure function - no timestamps, no randomness, no state - because
determinism is the whole contract. Formatting lives in formatters and never on the artifact
classes: an artifact is data, and its text is presentation.

## How it works inside

How references are kept canonical, the registry's two tiers, how artifact types are recovered
from references when reading JSON back, and the formatter classes are in [Inside
artifacts](HARNESS_ARTIFACTS_INTERNALS.md), for those working on the runtime itself.

> **Example:** [Data the model cannot alter](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/artifacts/PACKAGE.md) -
> a tool returns orders as artifacts, the agent answers with the references of the delayed
> ones, and the caller gets the tool's own objects back.
