# Package: ai.redouble.nucleo.harness.artifacts.tools

A model usually reads artifacts in a shortened form. The registry section of its context shows
every artifact, but a long field - a chart note, a full specification, a web page - appears as
a summary of a few hundred tokens instead of several thousand ([Artifacts: data the model
cannot alter](../PACKAGE.md)). That keeps the context affordable and keeps long artifacts
usable at all, and it leaves the model holding a summary of a field it may need to read in
full. A list is shortened the same way: the model sees how many rows it has and the first few,
and the other rows are held unseen.

The three tools of this package give it that access when it asks. Every thinker has them in its
palette without declaring them. Each takes a reference, looks it up in the registry of the
agent's own conversation, and returns what is stored there. Reading changes nothing, and the
content reaches the model as a tool's result, so the model still never carries the data
itself: the artifact your code receives stays the one the tool produced.

## `get_artifact_field`: one field in full

The model names an artifact by its reference and a field by its name, in snake_case or
camelCase as it prefers; fields the artifact inherits count too. The tool returns the field's
content, its full length, and whether more remains. The content is the whole value in the
form the model was shown a summary of: a text as it is, and any other value, a list or a map
or an object, as its JSON, with an artifact inside it as its reference. A value inside a
field is read by a path, the field's name followed by the JSON pointer to the value, such as
`data/results/0/title`. A field marked `@LLMContextIgnore` is no field for this tool. A long
field comes back in parts: at most
`max_chars` characters (10,000 unless the model asks for another amount), starting at
`offset` (the beginning unless it asks otherwise), so the model can read a very long field
part by part.

A reference that names no artifact in this conversation is refused with
`ResourceNotFoundException`, a mistake the model can correct: a model that invents or
misremembers a reference never receives another artifact's content. A field name the artifact
does not have is refused with `InvalidInputException`, whose message lists the fields the
artifact does have, and so is a path that leads to nothing inside a field. A field that exists and holds null returns no content, because null is
what the data is.

## `search_artifact_content`: finding text across artifacts

The model gives a string, and the tool finds it - exactly, ignoring case - in every text
of every artifact in the registry, including the iterands of a list, artifacts nested inside
others, and the texts inside a field that is a list or a map. A field marked
`@LLMContextIgnore` is not searched. Each match comes back with the reference of its artifact,
the path of the text, which is what `get_artifact_field` takes as the field name, the
position, and a snippet of the surrounding text. Two optional filters narrow the search: `artifact_ref` to one
artifact, and `artifact_type` to artifacts whose class name contains the given word, such as
`citation` or `webpage`.

The model decides how much comes back: `max_results` matches (10 unless it asks for another
number) with `context_chars` characters of text on each side (200 unless it asks otherwise).
Neither has a ceiling. A `max_results` below 1 or a negative `context_chars` is refused with
`InvalidInputException`. A search that finds nothing is an empty result, and so is a search
limited to a reference that names no artifact.

## `read_rows`: the rows of a list

The model names a list by its reference, the first row to read (`from_row`, counted from 1,
the first row unless it asks otherwise) and how many (`how_many`, 10 unless it asks for
another number; there is no ceiling). The tool returns those rows with the number of rows the
list holds, and the model sees them in full from then on, each under the reference it already
had in the list. Nothing is fetched or searched again: the rows are the ones the list holds.
A model that searched for twenty rows reads all twenty with one more call and copies no
references to do it.

A run that starts inside the list and reaches past its end returns the rows there are. A
reference that names no artifact is refused with `ResourceNotFoundException`. A reference to
something that is no list, a `from_row` or `how_many` below 1, and a `from_row` past the end
of the list are refused with `InvalidInputException`; the last says how many rows the list
holds.

## Where the registry comes from

All three tools read the registry of the thinker that calls them, which the thinker hands them
before each call. Run outside a thinker, with no registry to read, each fails with
`SystemException`. An agent called by another agent reads its own registry, which holds only
the artifacts it was handed ([Tools, thinkers and doers](../../../tools/PACKAGE.md)), so a
sub-agent can reach what it was given and nothing else.

## How it works inside

Why retrieval goes through the registry, the conveyance guarantees, how the registry reaches a
tool, and the classes of this package are in [Inside the artifact
tools](HARNESS_ARTIFACTS_TOOLS_INTERNALS.md), for those working on the runtime itself.
