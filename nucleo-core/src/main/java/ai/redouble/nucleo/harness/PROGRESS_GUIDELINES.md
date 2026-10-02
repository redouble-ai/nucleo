# Job Progress Reporting Guidelines

A person waiting on a job sees it start and, much later, end. In between there is nothing,
unless the job says what it is doing. Nucleo already reports a great deal without any code of
yours ([Events](../events/PACKAGE.md) lists it): every job's start and end, every retry, and,
for an agent, every tool it calls. What only your job's own code knows is where it is in its
own work: the third document of ten, the second of four phases. This page is how a job
reports that, and what makes a report worth reading.

## What is reported for you

The dispatcher publishes the lifecycle of every job, so a job never publishes these itself:

- `JobScheduled` when the job is submitted.
- `JobStartedEvent` once per attempt, after the job has been given what it declared it needs.
- At the end, one of `JobCompletedEvent`, `JobFailedEvent` with the error, `JobTimedOut` or
  `JobCancelled`, and after a job submitted directly under its workflow, a
  `WorkflowCompleteEvent` saying whether it succeeded.
- A retry event each time the job is run again ([Retry events](../events/retry/PACKAGE.md)).

A thinker also reports its own steps. For each tool call it publishes a progress event
`Tool Starting: Starting <tool>` at 0%, then a notification: `Tool Completed` at `INFO`, or
`Tool Failed`, a `WARNING` when the model can correct its input and an `ERROR` otherwise. It
publishes `Thinker Invoked` when it hands a question to another thinker, and notifications
when its conversation nears the model's context window and when it is compacted. An agent
you write on a thinker therefore reports its steps as it is.

## Reporting progress

Inside `execute`, publish through the `JobContext` the method receives. `publish` with a
message and a percentage sends a `JobProgressEvent`:

```java
context.publish("Loading data", 0);
context.publish("Processing records", 25);
context.publish("Applying changes", 50);
context.publish("Writing results", 75);
context.publish("Operation finished", 100);
```

The percentage tells a user interface which phase the job is in: 0 is starting, 1 to 99 is
progressing, 100 is completing (as the event's `msgType()`, `STARTING`, `PROGRESSING` and
`COMPLETING`). A message without a percentage, `context.publish("Waiting for the registry")`,
is a status update (`STATUS_UPDATE`). `publishUserProgress("Analyzing", "Document 3 of 10",
50)` is the same progress event with a title in front of the message: its text is
`Analyzing: Document 3 of 10`.

For work of a known number of steps, compute the percentage from the step:

```java
int percent = (currentStep * 100) / totalSteps;
context.publish(String.format("Step %d of %d", currentStep, totalSteps), percent);
```

In a long loop, report every few items and on the last one:

```java
for (int i = 0; i < files.size(); i++) {
    if (i % 10 == 0 || i == files.size() - 1) {
        context.publish(String.format("File %d of %d", i + 1, files.size()), (i * 100) / files.size());
    }
    // process files.get(i)
}
```

## Telling a person something

A notification is a discrete message, something a person should know beyond the steps: a
phase's summary, a finding, a warning about something the job carries on past.

```java
context.publishUserNotification(UserNotificationEvent.Severity.INFO, "Analysis complete", "Found 127 relevant items");
```

The severity says how much it matters:

- `INFO` - general information.
- `SUCCESS` - something succeeded.
- `WARNING` - something the user should know about, which does not stop the job.
- `ERROR` - a problem that needs attention. A failure that ends the job needs no notification:
  its `JobFailedEvent` already says so.

The event log writes `INFO` and `SUCCESS` notifications at DEBUG, `WARNING` at WARN and
`ERROR` at ERROR ([Logs, traces, metrics and cost](observability/PACKAGE.md)), so the severity
also decides whether a notification shows at the usual log level.

## Streaming text

A job that produces text piece by piece, as a model streams it, publishes each piece as a
`ContentStreamEvent`, and marks the last with `StreamChunk.done()`:

```java
context.publish(new ContentStreamEvent(context.getSnapshot(), StreamChunk.of("Generated text...")));
context.publish(new ContentStreamEvent(context.getSnapshot(), StreamChunk.done()));
```

A chat window shows the pieces as they come; the last one is the event at 100%.

## When to report

Report progress for work a person waits on: operations longer than about five seconds,
processes of several steps or clear phases, loops over a batch. Leave it out for operations
under a second, single atomic steps, and internal utility jobs. A job that runs for thirty
seconds in silence looks stuck; one update every five to ten seconds is enough.

Use steady intervals: 0% at the start, 25%, 50% and 75% for the major phases, 100% at the
end. A jump from 5% to 87% to 93% says little. A percentage stays at or below 100; when the
total is not known, report phases by name.

## Writing the message

Write for the person watching, in their words, and say something specific: a count, a name,
an identifier.

- "Analyzing document 3 of 10", "Generated 47 embeddings", "Saving results" say what is
  happening.
- "Processing data", "Step 1" and "Executing HibernateDAO.persist()" say nothing to a user.

A message about a problem says what went wrong in the user's terms and, where it can, what to
do: "Unable to read file 'data.csv'. Please check the file format." or "Network timeout. The
service may be unavailable. Try again later." Exception names and line numbers, such as
"NullPointerException at line 247", belong in the log.
