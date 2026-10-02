# Package: ai.redouble.nucleo.harness.schema

A model answers in text. Your code wants values: a status, a list of order numbers, a date.
The step in between is where most code that uses models breaks. The model puts a sentence
in front of the JSON, renames a field, writes a list as one comma-separated string, puts
"N/A" where a date belongs, and the code that parses the reply has to survive all of it.

In Nucleo you never parse a model's text. You write the answer you want as a Java class.
Nucleo shows the model exactly what that class looks like, reads the reply back into an
instance of it, and checks it the way you would check any input from outside your
program. When the reply does not fit, the model is told what was wrong and answers again,
before your code sees anything. What your code receives is an object of the class you
declared.

The same holds the other way round. When a model calls one of your tools, the tool's input
class is described to the model in the same way, and the arguments reach your tool as an
instance of that class.

## Declaring an answer

The answer of [your first agent](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/agent/PACKAGE.md)
is this class:

<!-- sample: ../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/agent/OrderAnswer.java#answer -->
```java
public class OrderAnswer extends ThinkerOutput<SimpleReasoning> {
    @LLMRequired
    @LLMDescription("The reply to the customer, stating each order's status as the lookup returned it")
    private String reply;
    @LLMRequired
    @LLMDescription("The numbers of the orders looked up to write the reply")
    private List<String> ordersLookedUp;
```

The fields are what you want back. The model sees nothing of your class except the field
names and what you write about them, so the two annotations are where you tell it what
you want:

- **`@LLMDescription` says what belongs in the field.** Write it for the model as you would
  for a new colleague filling in a form. "The numbers of the orders looked up to write the
  reply" leaves no doubt; a field called `orders` on its own leaves plenty. Put one on
  every field a model fills.
- **`@LLMRequired` says the model must fill the field.** A reply that leaves it null or
  blank goes back to the model to be completed. An empty list is accepted: it means there
  were none.

Field names reach the model in snake_case, `ordersLookedUp` as `orders_looked_up`. A field
marked `@JsonIgnore` is not shown to the model at all.

An agent's answer extends `ThinkerOutput`, which adds two things to the fields you declare:
the artifacts the agent selected, handed to your code exactly as the tools produced them
([Artifacts: data the model cannot alter](../artifacts/PACKAGE.md)), and the model's
account of how it answered, below.

## Telling the model more

- **For a fixed set of values, use an enum.** The model is shown the values it may use,
  and a reply with any other value goes back to it with the list of the accepted ones. There
  is no need to repeat the values in the description.
- **For a format, add an example.** `@LLMExample("2026-09-22")` shows the model a sample
  value where a description alone leaves the format open.
- **Dates, numbers and yes-or-no fields are read generously but never guessed.** A
  `Boolean` field accepts "yes"; a `LocalDate` field accepts "2026-09-22T00:00". A value
  that cannot be read as the field's type goes back to the model.

## The model's reasoning

Beside the fields you declared, every answer carries the model's reasoning: what it says
about how it reached the answer. It is kept in the record of the run, so "why did it say
that?" always has an answer. The type parameter chooses its shape: `SimpleReasoning` is one
thought; `ToolSelectionReasoning` is why it chose, and how sure it is;
`ChainOfThoughtReasoning` is its approach and its steps; `AnalysisReasoning` is relevance
and caveats. When the model reasons natively, as models with extended thinking do, Nucleo
takes the reasoning from there and does not ask for the field.

## Large fields

Some fields hold more than is worth showing a model in full: a web page, a patent
specification, a protein sequence. Mark such a field `@LLMSummarizable` and, when its value
is long, the model is shown a shortened form while your code keeps the whole value. How it
may be shortened depends on what the data is:

| The field holds | Declare | The model sees |
|---|---|---|
| Prose worth summarizing: web pages, patents, abstracts | `@LLMSummarizable("web page content")` | a summary written by a model |
| Text a paraphrase would corrupt: clinical records, precisely formatted text | `@LLMSummarizable(value = "clinical note", llmSafe = false)` | the text cut short, never reworded |
| Data no model should rewrite: chemical structures, sequences, code | `@LLMSummarizable(staticSummary = "SMILES string")` | a fixed phrase in place of the value |

`size` sets how long a summary may be: `BRIEF`, `SHORT` or `PARAGRAPHS`. On an artifact, a
model that needs the full value of a summarized field can ask for it with the
`get_artifact_field` tool ([Artifact tools](../artifacts/tools/PACKAGE.md)).

## Keeping a field from the model

`@LLMContextIgnore` leaves a field out of everything the model is shown: an internal id, a
raw payload, a value only your code needs. The field stays on the object and travels with
it.

## How it works inside

The schema renderings, the one mapper behind every read and write, and the rules of the
lenient parse are in [Inside the serializer](SERIALIZER.md), for those working on the
runtime itself.
