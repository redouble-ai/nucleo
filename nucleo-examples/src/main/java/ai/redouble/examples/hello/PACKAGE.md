# Package: ai.redouble.examples.hello

The smallest Nucleo program asks a model one question about a piece of text and prints the
answer. It shows the three things every later example builds on: the runtime that carries
out work, the way a program asks for a model without naming one, and an answer that arrives
as a Java object with fields you can read.

## The code

<!-- sample: HelloModel.java#hello -->
```java
JobDispatcher dispatcher = JobDispatcher.getInstance();
dispatcher.start();
try {
    QuickLLMQuestionInput question = new QuickLLMQuestionInput();
    question.setQuestion("Who is speaking, and where are they going?");
    question.setContext("Call me Ishmael. Some years ago, having little money in my purse,"
            + " I thought I would sail about a little and see the watery part of the world.");
    question.setGrade(Grade.SMALL);
    QuickLLMQuestionTool tool = new QuickLLMQuestionTool(Job.workflow("you", "hello"));
    tool.setInput(question);
    QuickLLMQuestionOutput answer = dispatcher.submit(tool).get();
    System.out.println(answer.getAnswer());
    System.out.println("confidence: " + answer.getConfidence());
}
finally {
    dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
}
```

Run the main from the IDE, as with every example. Change the question and the text to
anything; nothing else in the program cares what they say.

## The runtime

Everything Nucleo carries out, from one call to a model to a whole agent, is a job, and
`JobDispatcher` is the runtime that runs jobs. `start()` brings it up and `shutdown` lets
the jobs in flight finish before it stops. In a Spring Boot or Quarkus application the
integration starts and stops it with the application.

`dispatcher.submit(tool)` hands the dispatcher a job and returns a `JobHandle` at once; the
job runs on a virtual thread of its own and `get()` waits for its result. Before the call
to the model goes out, the dispatcher admits it against the model's rate limits, waiting
until the call fits within them.

`Job.workflow("you", "hello")` names the piece of work this job belongs to: who it is for
and what it is. Every job belongs to a workflow, and costs, events and records are kept per
workflow.

## Asking for a model without naming one

`QuickLLMQuestionTool` is a tool that ships with Nucleo. A tool is a piece of code with a
typed input and a typed output; [Your first tool](../tool/PACKAGE.md) writes one. This one's
whole work is a single question to a model. Its input carries the question, the text it is
about, and the grade.

No model is named anywhere in the program. `Grade.SMALL` is a rung on a ladder of model
capability that runs `MICRO`, `SMALL`, `MEDIUM`, `LARGE`, `XL`, `MEGA`: the code says how
much model the work needs, and the deployment's catalog of models says which model serves
that rung, at what price and through which provider. The same program runs on Anthropic,
OpenAI or Bedrock, served by whichever of them holds a credential in the environment.
[Set up and run the demo](../../../../../../../../AGENTS.md) says how to provide one.

When the catalog does not name the entry that serves SMALL, the runtime chooses one it can
call and names its choice in the log. With no catalog of the account's own, it chooses from
the small set of entries each provider library ships;
[The catalog, grades and the picker](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/models/PACKAGE.md)
shows how an account writes its own.

## The answer

The answer arrives as a `QuickLLMQuestionOutput`, a plain Java object: `getAnswer()` is the
answer and `getConfidence()` the model's confidence in it, from 0.0 to 1.0. The model's
reply has already been read into the object and checked against its declared fields, so
there is no text to parse. [Your first agent](../agent/PACKAGE.md) declares an answer class
of its own.

## Next

[Your first tool](../tool/PACKAGE.md) writes a tool from code of your own and calls it the
same way.
