# Package: ai.redouble.nucleo.harness.artifacts.tools

A model usually reads artifacts in a shortened form. The registry section of its context shows
every artifact, but a long field - a chart note, a full specification, a web page - appears as
a summary of a few hundred tokens instead of several thousand ([Artifacts: data the model
cannot alter](../PACKAGE.md)). That keeps the context affordable and keeps long artifacts
usable at all, and it leaves the model holding a summary of a field it may need to read in
full.

The two tools of this package give it that access when it asks. Every thinker has both in its
palette without declaring them. Each takes a reference, looks it up in the registry of the
agent's own conversation, and returns what is stored there. Reading changes nothing, and the
content reaches the model as a tool's result, so the model still never carries the data
itself: the artifact your code receives stays the one the tool produced.

## `get_artifact_field`: one field in full

The model names an artifact by its reference and a field by its name, in snake_case or
camelCase as it prefers; fields the artifact inherits count too. The tool returns the field's
content, its full length, and whether more remains. A long field comes back in parts: at most
`max_chars` characters (10,000 unless the model asks for another amount), starting at
`offset` (the beginning unless it asks otherwise), so the model can read a very long field
part by part.

A reference that names no artifact in this conversation is refused with
`ResourceNotFoundException`, a mistake the model can correct: a model that invents or
misremembers a reference never receives another artifact's content. A field name the artifact
does not have is refused with `InvalidInputException`, whose message lists the fields the
artifact does have. A field that exists and holds null returns no content, because null is
what the data is.

## `search_artifact_content`: finding text across artifacts

The model gives a string, and the tool finds it - exactly, ignoring case - in every text field
of every artifact in the registry, including the iterands of a list and artifacts nested inside
others. Each match comes back with the reference of its artifact, the field, the position, and
a snippet of the surrounding text. Two optional filters narrow the search: `artifact_ref` to one
artifact, and `artifact_type` to artifacts whose class name contains the given word, such as
`citation` or `webpage`.

The model decides how much comes back: `max_results` matches (10 unless it asks for another
number) with `context_chars` characters of text on each side (200 unless it asks otherwise).
Neither has a ceiling. A `max_results` below 1 or a negative `context_chars` is refused with
`InvalidInputException`. A search that finds nothing is an empty result, and so is a search
limited to a reference that names no artifact.

## Where the registry comes from

Both tools read the registry of the thinker that calls them, which the thinker hands them
before each call. Run outside a thinker, with no registry to read, either fails with
`SystemException`. An agent called by another agent reads its own registry, which holds only
the artifacts it was handed ([Tools, thinkers and doers](../../../tools/PACKAGE.md)), so a
sub-agent can reach what it was given and nothing else.

## How it works inside

Why retrieval goes through the registry, the conveyance guarantees, how the registry reaches a
tool, and the classes of this package are in [Inside the artifact
tools](HARNESS_ARTIFACTS_TOOLS_INTERNALS.md), for those working on the runtime itself.
