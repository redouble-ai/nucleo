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

A deployment can reword a description without touching the class: see
[Descriptions kept outside the code](../annotations/PACKAGE.md).

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
- **For the name people use, add `@DisplayName`.** `@DisplayName("Claim status")` on a field
  called `statCd` is written into the schema as the field's `title`, so the model connects
  the word a person says with the field, and a client that renders a form from the schema
  has its label. A field that is itself an object keeps its type's name as `title`.
- **Dates, numbers and yes-or-no fields are read generously but never guessed.** A
  `Boolean` field accepts "yes"; a `LocalDate` field accepts "2026-09-22T00:00". A value
  that cannot be read as the field's type goes back to the model.
- **Dates and times are written as ISO-8601 text, and the text says which kind it is.** A
  `LocalDate` is a plain date, `"2021-07-28"`, with no time and no zone. A timestamp
  (`Instant`, `OffsetDateTime`, `ZonedDateTime`) always carries its offset:
  `"2026-05-28T16:50:29.597415Z"`, `"2026-05-28T18:50:29.597415+02:00"`. A zone's name is
  never written, since it is no part of an ISO-8601 date-time. A `LocalDateTime` and a
  `LocalTime` gain no offset: `"2021-07-28T16:50:29"`, `"16:50:29"`. This is the shape a
  field's schema publishes (`date`, `date-time`, and `time` for a time of day), in a tool's
  result as in its input, so a value handed out is accepted back. Every written value reads
  back to an equal value: a timestamp keeps the offset it was written with, and a
  `ZonedDateTime` of a named zone reads back as the same date, time and offset, its zone
  being that offset. A `Duration` is a number of seconds.
- **The type of a date field decides how strictly a model's text is read.** A `LocalDate`
  or `LocalDateTime` field is lenient: it reads whatever `Temporals.parseLenient` reads,
  `"07/28/2021"` and `"28.07.2021"` as well as ISO text. An `Instant`, `OffsetDateTime`,
  `ZonedDateTime` or `LocalTime` field is strict: it reads ISO-8601 text and refuses anything
  else, and the refusal goes back to the model as a correctable error naming the field.
  Declare the lenient types where a model's own spelling of a date is welcome, and the
  strict ones where the exact moment matters. A call over MCP is held to ISO-8601 text
  whatever the type.

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
is long, the model is shown a summary while the object keeps the whole value.

The annotation goes on a field of an artifact, a class that implements `Artifact`
([Artifacts](../artifacts/PACKAGE.md)), and nowhere else. A model that is shown a summary
has to be able to read the whole value, and an artifact is what it can read back, by its
reference, with the `get_artifact_field` tool
([Artifact tools](../artifacts/tools/PACKAGE.md)). The field may be of any type: a text is
summarized as it is, and any other value as the JSON it is written as. The placement is
checked for you three times:

- at compile time, by the annotation processor in `nucleo-processor`, once it is on the
  compiler's `annotationProcessorPaths`: a misplaced field is a compile error on its line;
- in a Quarkus build, which fails naming every misplaced field;
- when `JobDispatcher` starts, which reads the class files of the application and refuses
  to start with a `SummarizableOutsideArtifactException` that names every misplaced field.

How a value is summarized depends on what the data is:

| The field holds | Declare | The model sees |
|---|---|---|
| Prose worth summarizing: web pages, patents, abstracts | `@LLMSummarizable("web page content")` | a summary written by a model |
| Text a paraphrase would corrupt: clinical records, precisely formatted text | `@LLMSummarizable(value = "clinical note", llmSafe = false)` | the text cut short, never reworded |
| Data no model should rewrite: chemical structures, sequences, code | `@LLMSummarizable(staticSummary = "SMILES string")` | a fixed phrase in place of the value |
| A value with a structure of its own: JSON, a stack trace, a list of identifiers | `@LLMSummarizable(value = "job input", summarizer = MySummarizer.class)` | what your class writes for the value |

A value is summarized when it is longer than `threshold`: the length of a text and, for any
other value, the length of its JSON. A field's own summarizer is a class that implements
`FieldSummarizer<T>`, where `T` is the field's type, and has a public no-argument
constructor; the value is handed to it as it is. A field that names one is never sent to a
model for its summary.

You do not ask for summaries. A model's summary is made once, by a summarization job, when a
thinker or a doer is about to send a conversation that holds the artifact, and from then on
the model is shown that summary every time. A summary of any kind is kept on the artifact
(`Artifact.getSummaries()`) and stored with a persisted conversation, beside the artifact,
which is always stored with its whole value. Where no model's summary is made yet, as in a
conversation's registry before its first call, the text is shown cut, marked with its full
length, and nothing is kept. A text longer than 200,000 characters is cut and never sent to
a model.

Summarizing saves tokens and never fails a serialization. When a field's own summarizer or
the summarization job throws or returns nothing, the failure is logged, nothing is kept and
the value is shown cut for that write; the job is asked again the next time a conversation
is sent. A cancelled job asks for no further summary.

`size` sets how long a summary may be: `BRIEF`, `SHORT` or `PARAGRAPHS`.

A value that is a tree, such as the result of an MCP tool, can keep its shape for the model:
with `preSummarized = true` each text leaf longer than `threshold` is shown as its own
summary, and the rest of the tree is shown as it is.

## Keeping a field from the model

`@LLMContextIgnore` leaves a field out of everything the model is shown: an internal id, a
raw payload, a value only your code needs. That covers the prompt, the listing of an
artifact in the conversation's registry, and what the artifact tools read and search. The
field stays on the object, travels with it and is stored with a persisted conversation.

## How it works inside

The schema renderings, the one mapper behind every read and write, and the rules of the
lenient parse are in [Inside the serializer](SERIALIZER.md), for those working on the
runtime itself.
