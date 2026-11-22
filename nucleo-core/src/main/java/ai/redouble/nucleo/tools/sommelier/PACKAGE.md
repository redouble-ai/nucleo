# Package: ai.redouble.nucleo.tools.sommelier

A thinker's catalog is the set of tools it may ask for
([Palettes and the tool registry](../registry/PACKAGE.md)). A short catalog is listed to the
model inside `request_tools`, every name with its description. A long one cannot be: the list
costs tokens on every turn, and a model picks worse from a long list of tools whatever space
the list takes. Connect a few MCP servers, or give an assistant one search tool per table of
a database, and a thinker has hundreds.

The sommelier is what a thinker with a long catalog gets in place of the list. The thinker's
model is shown no tool names at all. It has one tool, `find_tools`, to which it states what
it needs in its own words, and the answer names the two or three tools that serve the need.
It then activates the ones it chooses with `request_tools`, as it would a listed tool.

## Where the line is

`SommelierSettings.listedTools`, 30 as shipped, is the longest catalog that is listed.

| The thinker's catalog | `request_tools` | `find_tools` |
|---|---|---|
| 30 tools or fewer | lists every tool, name and description | not offered |
| 31 tools or more | names no tool and takes any name of the catalog | offered |
| empty | not offered | not offered |

The palette follows the catalog turn by turn: a catalog that grows past the limit while a
thinker runs stops being listed on the next turn, and one that a thinker's
`reconcileCatalog` narrows under it is listed again. A read-only thinker has no
`request_tools`, since that tool changes the palette, and so is given no `find_tools` either.

## What the model sees

```
find_tools(need = "Which support cases were reopened after being closed?")

{
  "tools": [
    {"name": "search_cases", "description": "Search the support cases", "loaded": false},
    {"name": "search_case_history", "description": "Search the status changes of a case", "loaded": false}
  ],
  "catalog_size": 212
}
```

- `tools` is every tool that fits, in the catalog's order, each with its description and
  whether the thinker already holds it
- `catalog_size` is how many tools were read to produce the answer
- No tool fitting is an answer: an empty `tools` and the catalog size
- A lookup that could not be completed is an error the model reads. It is never an empty
  answer
- A blank `need` is refused before anything is looked up
- Tools that look alike come back together. When several tools cannot be told apart for the
  need, such as the same record kept by different applications, all of them are named and
  the thinker, which knows its task, chooses

`find_tools` names tools and admits none. A tool reaches the thinker through `request_tools`
and the admission rules registered for it, exactly as a listed tool does, and a name the
catalog does not hold is rejected there.

## How the catalog is read

`ModelSommelier`, the sommelier the runtime starts with, has a model read every tool of the
catalog in full and name the ones that fit. Nothing is filtered or ranked before a model has
read it.

1. Each tool becomes a page (`ToolPage`): its name, its description, and the words of its
   input and output definitions, which are the field names, titles, descriptions and
   enumerated values. Schema structure is left out.
2. The pages are cut into the fewest parts of about `SommelierSettings.partTokens` tokens,
   50,000 as shipped, of even size, a token counted as four characters.
3. One model call reads each part, all at once, and names the tools of its part that fit.
4. When there was more than one part and the parts named more than one tool, one more call
   reads the named tools side by side, and its answer is the lookup's. A part sees only its
   own tools and names its best candidate whatever the others hold, so the parts together
   name more tools than fit.

A call that fails fails the lookup. A name a model returns that the catalog does not hold
is dropped and logged.

## Which model reads it

The calls run at the `SMALL` grade, served by whichever model the deployment pins to it
([Models](../../harness/models/PACKAGE.md)). **The lookup works, and is tested, with Claude
Haiku 5.5 and stronger models.** Weaker models returned long answers, named tools that do not
exist, or returned nothing (the table below). A deployment whose `SMALL` grade is served by
a weaker model, or that has measured a better choice, names the catalog entry in a
`ModelRole`:

```java
ModelSommelier sommelier = new ModelSommelier();
sommelier.setRole(new ModelRole("tool-lookup", "<catalog id>", Grade.SMALL));
ToolHub.getInstance().setSommelier(sommelier);
```

The role's entry then serves every lookup, and the role's grade stands in when the entry
cannot.

## Your own sommelier

`Sommelier` is one method. It receives the need and the asking thinker's catalog and returns
a job that answers with the names of the tools that fit; `find_tools` runs that job and
waits for it. An implementation may be one model call, a search over an index you keep, a
lookup in another service, or a different arrangement of model calls.

```java
public class IndexedSommelier implements Sommelier {
    @Override
    public Job<SommelierAnswer> pair(Identifiable parent, String need, Collection<ToolProvider> catalog) {
        return new IndexLookup(parent, need, catalog);
    }
}

ToolHub.getInstance().setSommelier(new IndexedSommelier());
```

One sommelier serves every thinker of the process. Whatever it answers, `find_tools` passes
on only names of the asking thinker's catalog.

## What was measured

The design follows measurements made in November 2026 on two catalogs. Each question has
known right tools and is asked in words that do not name them.

- **Bare**: 212 tools generated from the tables of a database, one search tool per table,
  with a name, the column names and almost no descriptions. The least a tool can say about
  itself
- **Described**: 356 tools of several applications, each with a written description and
  typed input and output

Claude Haiku 5.5, 120 questions per catalog:

| Catalog | Read as | Right tool in the answer | Tools in the answer, median | Wrong tools, mean |
|---|---|---|---|---|
| Bare, 37,000 tokens | one part | 120 of 120, in each of four runs | 2 | 1.0 to 1.3 |
| Described, 210,000 tokens | four parts of 53,000 | 120 of 120 | 2 | 0.75 |
| Described | eight parts of 26,000 | 119 of 120 | 2 | 0.8 |
| Described | one part of 210,000 | 104 of the 109 that completed | 6 | 6.3 |

Other models, the bare catalog in one part, 40 of the questions:

| Model | Right tool in the answer | Tools in the answer, median | Wrong tools, mean |
|---|---|---|---|
| Amazon Nova Micro | 16 of 40 | 8 | 20 |
| GPT-OSS 120B | 28 of 40 | 2 | 2.0 |
| Claude Haiku 5.5 | 37 of 40 | 2 | 0.8 |
| Claude Sonnet 5.5 | 40 of 40 | 2 | 1.3 |
| Claude Opus 5.5 | 39 of 40 | 2 | 0.8 |
| GPT-5.6 Sol | 39 of 40 | 2 | 1.0 |
| GPT-6 Astra | 40 of 40 | 2 | 1.2 |

These rows ran before the instruction about look-alike tools was written; with it Haiku
found the right tool for all 40, and for all 120 in the table above. Opus's miss was a
refusal by the provider's content filter, on a question the other models answered.

What the numbers decided:

- **A part is about 50,000 tokens.** One call holding 210,000 tokens of tools found fewer of
  them and returned three times as many as four calls of 53,000
- **A model reads the tools side by side.** One model call per tool, on Claude Opus 5.5,
  found the right tool for 39 of 40 questions with 4.5 wrong tools per answer, at a hundred
  times the cost of one call over the whole catalog on Haiku
- **The last call.** Parts alone found the right tool as often and returned 2.6, 4.4 and
  6.5 wrong tools per answer for two, four and eight parts of the bare catalog. With the
  last call the answer is back to about two tools and 118 of 120 right
- **Look-alikes are all named.** Told nothing about them, Haiku picked one of several
  identical-looking tools by guessing on some runs and named them all on others, for 117 to
  119 right of 120. Told to name them all, it scored 120 in each of four runs
- **No search in front of the model.** An index over the bare catalog, by words, by
  embeddings or by both, held the right tool in its first five results for 74% of the
  questions at best and in its first ten for 86%, with no sign of which questions it
  missed. Used as a filter, it would cap what the model can find
- **The limit of 30.** A model picks a tool worse as the list it picks from grows, and the
  published measurements place the bend at 30 to 50 tools

A lookup of the bare catalog cost $0.004 on Claude Haiku 5.5 and one of the described
catalog $0.03, at list price without prompt caching.

## Settings

| Setting | What it sets | Shipped |
|---|---|---|
| `SommelierSettings.listedTools` | the longest catalog listed to a thinker's model; a longer one is found through `find_tools` | 30 |
| `SommelierSettings.partTokens` | about how many tokens of tool pages `ModelSommelier` puts in one model call | 50,000 |

## Classes

| Class | What it is |
|---|---|
| `Sommelier` | The seam: a need and a catalog in, a job that names the fitting tools out |
| `ModelSommelier` | The shipped implementation, with an optional `ModelRole` |
| `FindToolsTool`, `FindToolsInput`, `FindToolsResult` | The `find_tools` tool a thinker's model calls |
| `SommelierLookup` | One lookup of `ModelSommelier`: the parts, their calls and the last call |
| `SommelierCall` | One model call over a set of tool pages, with the instruction the model is given |
| `SommelierQuestion`, `SommelierAnswer` | What a call is asked and what it answers |
| `ToolPage` | A tool as text for a model to read |
| `SommelierSettings` | The two settings above |

`FindToolsTest` pins what a thinker is offered and what `find_tools` answers;
`SommelierLookupTest` pins the page, the parts, the calls of a lookup and the model they ask
for. Both run on scripted jobs in place of model calls.
