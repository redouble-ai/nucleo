# Package: ai.redouble.nucleo.harness.annotations

What a tool tells a model about itself is written in annotations: `@ToolDescription` on the
tool, `@LLMDescription` on its input and output and on their fields. That text is the first
thing people want to change after the code ships. A description misleads the model, a field
means something particular in one organisation, a tool came from a library and speaks its
author's language. If the text lives only in the annotation, every such change is a release.

This package lets a deployment keep that text wherever it likes, a database table for
example, with the annotation in the code as the default. Nothing is edited in the class.
[Prompts](../../prompt/PACKAGE.md) do the same for a thinker's instructions; this is the same
idea for everything a tool and its types say.

## See it

A tool as it is written today:

```java
@ToolName("search_cases")
@ToolDescription(value = "Search the support cases", readOnly = true)
public class CaseSearchTool extends AbstractTool<CaseSearchInput, CaseSearchResult> { ... }

public class CaseSearchInput {
    @LLMDescription("Words to look for in the case title")
    private String title;
}
```

A deployment sets a resolver once, at startup:

```java
Annotations.setResolver(keys -> myStore.find(keys));
```

and from then on its store decides what the model reads:

| Key | Stored value |
|---|---|
| `com.acme.support.CaseSearchTool@ToolDescription` | Search the support cases, open and closed |
| `com.acme.support.CaseSearchInput.title@LLMDescription` | Words in the title. Customers call a case a ticket |

A key the store does not hold reads as the code wrote it. With no resolver set, every
annotation reads as the code wrote it and nothing is asked of anyone.

## The key

The key of an annotation is derived from where it stands, so nobody chooses one:

| The annotation is on | Key | Written by |
|---|---|---|
| a class | `<class name>@<Annotation>` | `Annotations.keyOf(owner, type)` |
| a field | `<class name>.<field>@<Annotation>` | `Annotations.keyOf(owner, field, type)` |

The class name is the binary name, so a nested class is `Outer$Inner`. The annotation is its
simple name.

A field a class inherits has two keys. The store is asked under the class being described
first and under the class that declares the field second. So one input class that many tools
extend is described once, under the declaring class, and reworded for one tool under that
tool's own input class. The first of the two keys that holds something supplies the whole
override.

## What is stored

An `AnnotationOverride` has two parts, and either may be left null:

- **`value`**: the annotation's `value`, as plain text. For an annotation whose `value` is a
  list of strings, `@LLMExample`, it is one entry per line; blank lines are dropped
- **`attributes`**: a JSON object of the annotation's other attributes, for example
  `{"readOnly": true}` for a `@ToolDescription` or `{"action": "Searching cases"}` for a
  `@DisplayName`

Whatever an override leaves out is the code's.

## The code decides what exists

A store restates an annotation the code carries. It cannot add one. A key for an annotation
the code does not have, on a class of the process or on a class that is not there at all, is
never asked for and has no effect, the same way an annotation on a class outside the
classpath has none.

So nothing outside the code can describe a class into a tool, or give a description, a
display name or a weight to something that declares none. To let a deployment
describe a field, put an `@LLMDescription` on it, with whatever default wording the code can
offer.

## Which annotations

| Annotation | What a store can set | Read by |
|---|---|---|
| `@ToolDescription` | the description, `readOnly` | `ClassToolProvider` |
| `@DisplayName` | the name, `action` | `ClassToolProvider`, `JobSnapshot` |
| `@ToolWeight` | `type`, `level`, `min`, `max` | `ClassToolProvider` |
| `@LLMDescription` | the description of a class or of a field | the schema of every input, output and artifact |
| `@LLMExample` | the examples of a field | the same |

`@ToolName` and `@TypeAlias` are not read through here: they are the identifiers a model
sends back and a stored record refers to. `@LLMRequired`, `@LLMSummarizable`,
`@LLMContextIgnore`, `@SchemaRefinedBy` and `@MCP` stay in the code as well.

A tool served over MCP is described from the same provider and the same schema as a tool
offered to a thinker, so a client of the server receives the stored text too.

## What is refused

A stored override is checked against its annotation when it is read, and one the annotation
cannot take throws `AnnotationOverrideException`, which names the key and what the
annotation accepts and repeats nothing that was stored:

- an attribute the annotation does not have
- an attribute of the wrong type: `readOnly` takes true or false, `min` a whole number,
  `type` one of the enum's names
- `value` placed among the attributes
- attributes that are not a JSON object
- a `value` for an annotation that has none, such as `@ToolWeight`
- text a baseline prompt guardrail refuses. A stored description is text a model reads, so it
  passes the same baseline guardrails as a substituted prompt before it is put in a tool's
  definition

A refused override is never degraded to the code's wording or to a tool without a schema:
the tool cannot be registered until the row is corrected.

A row that sets `readOnly` or a weight decides what a tool may do. The store a resolver reads
deserves the same care as the code.

## When a change is seen

The resolver is asked once per class, for the key of every annotation the class and its
fields carry, so a schema of fifty fields is one read of the store. What it answered is kept.

A deployment calls `Annotations.changed()` when its store changes. Everything kept is
dropped, and a tool's description and schemas are built again the next time they are asked
for, including on a provider that already exists. Until then a provider answers what it
answered before. `setResolver` and `clearResolver` have the same effect.

A tool already declared to a model in a conversation is not redefined
([the conversation](../conversation/PACKAGE.md)), so a change reaches conversations that
begin after it.

## Listing what can be stored

`ClassToolProvider.annotationKeys()` lists every key of one tool with what the code declares
for it: the keys of the tool class and of every class its input and output are described by.
`Annotations.keysOf(Class)` does the same for one class. An editing screen over a store shows
this listing with the stored wording beside it.

- An annotation is listed where the code carries it, and nowhere else
- A field is listed under the class that declares it

## The classes

| Class | What it is |
|---|---|
| `Annotations` | `setResolver`, `clearResolver`, `changed`, `generation`, `keyOf`, `keysOf`, and `of`, the read every reader goes through |
| `AnnotationResolver` | the deployment's store: one method, the keys of one class in, what is stored for them out |
| `AnnotationOverride` | what is stored for one key: `value` and `attributes` |
| `ResolvedAnnotation` | an annotation as it reads once the store was consulted; attributes are read by name |
| `AnnotationKey` | one line of the listing: a key and what the code declares for it |
| `AnnotationOverrideException` | a stored override its annotation cannot take |
