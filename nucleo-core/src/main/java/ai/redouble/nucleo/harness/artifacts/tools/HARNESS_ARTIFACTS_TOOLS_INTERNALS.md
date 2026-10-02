# Inside the artifact tools

This page is for people working on the runtime itself: why retrieval goes through the
registry, what conveyance guarantees, how the registry reaches a tool, and the classes of
`ai.redouble.nucleo.harness.artifacts.tools`. The guide for the two tools is [Artifact
tools](PACKAGE.md).

---

## Why retrieval goes through the registry

Handing the LLM the full object and asking it to carry the parts it needs would collapse the
property the artifact system is built on. Two things hold only because content travels outside the
model's output:

**Content integrity.** An agent cannot paraphrase, truncate, or renumber what it never writes. The
model's response schema has no slot for artifact content, so identifiers that look authoritative
and drift silently under paraphrase (DOIs, PMIDs, claim numbers, SMILES strings) cannot degrade
across a chain of agents.

**Capability decoupling.** A recipient agent receives conveyed artifacts without holding the tool
that produced them, that tool's permissions, or any credential for dereferencing the data. It has
the data because the framework carried it. This is what lets agents stay specialized: a downstream
agent that reasons over a claim record does not need the claim-fetching tool in its own registry,
and its tool list stays small.

Conveyance is **deep-equal**: field content preserved recursively to primitive values, along with
type information, the distinction between null and absent, and binary fidelity. Within a JVM the
recipient sees the same instance. Across a serialization boundary a round trip preserves those
properties without requiring a canonical byte sequence.

## Artifact Reference Format

All artifact references use the canonical format: `«artifact:type~uuid»` (with guillemet
delimiters). This is the format used in:
- HashMap keys in `ArtifactRegistry`
- Registry display sections shown to the LLM
- `@ref` placeholders in serialized messages
- `artifactRefs` lists in `ArtifactResponse`
- Tool input parameters (`artifactRef` fields)

`ArtifactRegistry.normalizeToKey()` is the single normalization function that maps any variant
(bare ID, with/without prefix, with/without guillemets) to this canonical format.

## Tools

| Tool | Purpose |
|------|---------|
| `get_artifact_field` | Retrieve the content of a specific field, in slices of `maxChars` from `offset`. Accepts snake_case or camelCase field names |
| `search_artifact_content` | Exact case-insensitive search across all String fields of every artifact, the reachable tier included |

Both tools read the live artifact via reflection. They walk the class hierarchy so inherited fields
are visible.

## Registry Injection

Tools need access to the conversation's artifact registry. The `ArtifactRegistryAware` interface
enables this:

```java
public interface ArtifactRegistryAware {
    void setArtifactRegistry(ArtifactRegistry registry);
    ArtifactRegistry getArtifactRegistry();
}
```

`AbstractThinker.submitToolCall()` checks if a tool implements this interface and injects the
registry before execution. A tool of this package dispatched without that injection - run
outside a thinker - fails with `SystemException`: the missing registry is the caller's
wiring fault, never a result the model reads.

The injected registry is not always the full one. When the invoked tool is itself a thinker whose
input declares `artifactRefs`, it receives `ArtifactRegistry.filter(refs)`: a registry holding only
the artifacts that were conveyed to it. A sub-agent sees what it was given and nothing else, which
bounds how much a compromised or misdirected agent can reach as well as how much it must reason
over.

## Classes

| Class | Purpose |
|-------|---------|
| `ArtifactRegistryAware` | Interface for registry injection |
| `GetArtifactFieldTool` | Retrieves full field content |
| `GetArtifactFieldInput` | Input: artifactRef, fieldName, offset, maxChars |
| `GetArtifactFieldOutput` | Output: content, fieldName, length, truncated flag |
| `SearchArtifactContentTool` | Searches across artifact content |
| `SearchArtifactContentInput` | Input: query, optional artifactType and artifactRef filters, maxResults, contextChars |
| `SearchArtifactContentOutput` | Output: list of matches, totalMatches |
| `SearchMatch` | Single match with snippet and position |
| `ReflectiveFields` | Shared field lookup for LLM-supplied names: snake_case/camelCase resolution up the class hierarchy (`find`), and the addressable field-name surface (`fieldNames`) |
| `KeyedRecord` | An artifact whose filterable fields are a dynamic name->value set rather than static Java fields - the bridge for record-shaped artifacts whose columns are decided at run time, so a list-processing predicate can address a data-defined field by the name the caller asked for |
