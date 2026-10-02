# Package: ai.redouble.examples.artifacts

When a model passes data along, it retypes it. Ask an agent which of a customer's orders are
delayed, and the order numbers and dates in its answer are the model's copy of what the tool
returned, written out again token by token. Most of the time the copy is right. Now and then
a digit changes, a date is rounded or a note is paraphrased, and nothing in the answer shows
it. With a chain of agents, each passing results to the next, every hop is another copy.

Nucleo keeps the data out of the model's hands. A tool can return its results as artifacts:
records the runtime keeps in a registry, each under a short reference. The model
reads the records in full and decides which ones matter, and it answers with their
references. Your code receives the records themselves, the objects the tool built, however
many agents stood between the tool and your code.

This page lists a customer's orders as artifacts and lets an agent pick the ones a question
asks for.

## The artifact

<!-- sample: OrderRecord.java#artifact -->
```java
@TypeAlias("order")
public class OrderRecord extends AbstractArtifact {
    @LLMDescription("The order number")
    private String orderNumber;
    @LLMDescription("PROCESSING, SHIPPED, DELIVERED or DELAYED")
    private String status;
    @LLMDescription("The date the order was promised for, as YYYY-MM-DD")
    private String promisedFor;
    @LLMDescription("What the warehouse or the carrier says about it")
    private String note;
```

An artifact is a plain data class that extends `AbstractArtifact`. `@TypeAlias("order")`
names its kind, and the name is the first part of every reference to one:
`«artifact:order~...»`. The `@LLMDescription` on each field tells the model what the field
holds, as it does on an answer in [Your first agent](../agent/PACKAGE.md).

`CustomerOrdersTool` returns a customer's orders as a list of `OrderRecord` and does nothing
else about artifacts. As the tool's result reaches the model, the runtime registers each
record in the registry of the agent's conversation and shows the model the record's
reference beside its fields.

## Selecting

`SelectingAgent` is an agent like the one in [Your first agent](../agent/PACKAGE.md), with
`CustomerOrdersTool` as its one tool and a system prompt that tells it to answer with the
references of exactly the orders the question asks for. Its answer class, `SelectedOrders`,
has one field of its own, a one-sentence `summary`. Like every agent's answer it extends
`ThinkerOutput`, and that is where the selection travels: the references the model gives,
and any it mentions in its text, are looked up in the registry when the agent finishes, and
`getArtifacts()` returns the records they name.

<!-- sample: SelectOrders.java#select -->
```java
JobDispatcher dispatcher = JobDispatcher.getInstance();
dispatcher.start();
try {
    SelectingAgent agent = new SelectingAgent(Job.workflow("you", "select-orders"));
    agent.setInput(new OrderQuestion("Which orders of customer C-100 are delayed?"));
    SelectedOrders answer = dispatcher.submit(agent).get();
    System.out.println(answer.getSummary());
    for (Artifact artifact : answer.getArtifacts()) {
        // The object the tool built, from the registry: no field of it passed through the model
        OrderRecord record = (OrderRecord) artifact;
        System.out.println(record.getArtifactRef() + " " + record);
    }
}
finally {
    dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
}
```

Asked "Which orders of customer C-100 are delayed?", the program prints the model's summary
and then each selected record with its reference. The summary is the model's own prose and
may paraphrase. The records are the tool's: an account number, a dosage or a promised date
in them is exactly what the tool wrote.

## Next

[Artifacts: data the model cannot alter](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/artifacts/PACKAGE.md)
covers artifacts in full. The next example,
[An agent that stays inside its case](../scope/PACKAGE.md), fixes what an agent may touch
before it starts.
