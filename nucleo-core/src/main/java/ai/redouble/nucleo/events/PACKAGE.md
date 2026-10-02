# Package: ai.redouble.nucleo.events

From outside, a running agent is a call that has not returned yet. Inside, a lot is
happening: the agent asks a model what to do, the model asks for a tool, the tool runs, a
provider answers "too many requests" and the call waits and goes again, and in the end an
answer comes back or the run fails. To show a user what the agent is doing, to raise an alert
when something fails, or to record what a run cost, your code has to learn about each of
these steps while it happens, as facts it can act on. A log line is written for a person to
read, and code that parses one breaks the day its wording changes.

Nucleo reports each step as an event: a small immutable Java object that says what just
happened to which job. Everything Nucleo runs is a job ([Your first tool](../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/tool/PACKAGE.md)
introduces jobs), so an agent's run is a tree of jobs: the agent, every model call it makes
and every tool it calls. Each of them publishes events from the moment it is submitted to the
moment it ends. The events go onto one message bus, the dispatcher's, and any code of yours
can subscribe to the kinds it cares about. The log lines, traces, metrics and cost figures of
[the next page](../harness/observability/PACKAGE.md) are all built by subscribers of this
same bus.

## What a job publishes

In the order a job meets them:

- **`JobScheduled`** when the job is submitted and waits in the dispatcher's queue.
- **`JobStartedEvent`** when it starts to run, once it has been given everything it declared
  it needs ([Admission](../harness/admission/PACKAGE.md) is how). When the dispatcher runs
  the job again, it publishes another, with the next attempt number.
- **Progress and notifications** while it runs: a `JobProgressEvent` for a step or a
  percentage, a `UserNotificationEvent` for something a person should know, a
  `ContentStreamEvent` for each piece of text a model streams. The thinkers publish these
  for every tool they start and finish, so an agent reports its steps with no code of yours;
  [Reporting progress from a job](../harness/PROGRESS_GUIDELINES.md) shows how your own jobs
  do the same.
- **A retry event** when the dispatcher runs the job again instead of failing it: a
  provider's rate limit or server error, a reply cut off at its token budget, a reply that
  did not parse. [Retry events](retry/PACKAGE.md) lists them.
- **One terminal event** when a job that ran ends: `JobCompletedEvent` with the result,
  `JobFailedEvent` with the error, `JobTimedOut`, or `JobCancelled`. Each carries the
  responses of the model calls the job made, which is how the cost ledger prices them. A job
  that never ran because a job it depended on failed ends with a `DependencyFailureEvent`.
- **`WorkflowCompleteEvent`** after the terminal event of a job submitted directly under its
  workflow (`Job.workflow(...)`), saying whether it succeeded.

Every event about a job carries a `JobSnapshot` of the job at that moment (`snapshot()`):
its workflow id, job id, display name, parent job and state, which is how you tell whose
event it is. Every event also carries the instant it was built (`timestamp()`) and a
one-line description (`message()`). An event meant for a person implements `HumanReadable`, whose
`getHumanMessage()` is the text to show.

## Families of events

An observer rarely wants one exact event class. It wants "everything about how jobs end",
or "anything a user should see". So each event Nucleo publishes belongs to one family, a
sealed Java interface you can subscribe to as a whole (the one exception closes this page):

| Family | What it tells you |
|---|---|
| `LifecycleEvent` | A job was scheduled, started, or ended; its sub-family `TerminalEvent` is the four endings plus `DependencyFailureEvent`. Also an orchestrator going idle and resuming between the messages of a chat (`OrchestratorIdleEvent`, `OrchestratorResumedEvent`). |
| `ProgressEvent` | A job reported progress, or a model streamed text. |
| `NotificationEvent` | A job told a person something: `UserNotificationEvent`, with a severity. |
| `RetryEvent` | The dispatcher is running a job again ([Retry events](retry/PACKAGE.md)). |
| `WorkflowTerminationEvent` | A workflow's work has ended: `WorkflowCompleteEvent`. |
| `OperationalEvent` | A job was held, admitted, refused or released by one of the limits it runs under: `LimiterEvent`, one per change, so many of them. |
| `SystemEvent` | The dispatcher itself started, is stopping, stopped, or hit an error: `SchedulerEvent`. |
| `HeartbeatEvent` | Scheduled work was requested, stored or fired ([Heartbeats](heartbeat/PACKAGE.md)). |

`TerminalEvent` also splits by verdict: `SuccessEvent` is the completion, `FailureEvent` is a
failure, a timeout or a dependency failure, and a cancellation is neither.

A family you subscribe to delivers every member of it and nothing else, so a
`LifecycleEvent` subscriber sees starts and endings and never a progress event. Because the
families are sealed, the compiler checks a `switch` over one: a switch that lists every
member needs no `default`, and a member added to the family later makes it fail to compile,
so your code learns about the new case at build time.

## Subscribing

An observer is a `JobObserver`, whose one method, `observe`, receives each event. A lambda
is enough. You subscribe it on the started dispatcher with the event class or family you
want, and the observer is typed to that class:

```java
JobDispatcher dispatcher = JobDispatcher.getInstance();
dispatcher.start();

// every job that fails, whatever it is
dispatcher.subscribe(failed -> System.out.println(failed.message()), JobFailedEvent.class);

// every retry, whichever kind
dispatcher.subscribe(retry -> System.out.println(retry.message()), RetryEvent.class);

// every ending of an OrderAgent, the agent of "Your first agent"
dispatcher.subscribe(ended -> System.out.println(ended.message()), TerminalEvent.class, OrderAgent.class);
```

One observer receives one type. To handle several kinds, subscribe one observer per type,
or subscribe once to the family that holds them and match on the event:

```java
dispatcher.subscribe(ended -> {
    switch (ended) {
        case SuccessEvent done -> System.out.println("done: " + done.message());
        case FailureEvent failed -> System.out.println("failed: " + failed.message());
        case JobCancelled cancelled -> System.out.println("cancelled: " + cancelled.message());
    }
}, TerminalEvent.class);
```

To follow one workflow, one user's request say, `observeWorkflow` delivers every event
whose job belongs to it:

```java
Identifiable workflow = Job.workflow("you", "orders");
dispatcher.observeWorkflow(workflow.getWorkflowId(), event -> System.out.println(event.message()));
```

Each of these calls returns a `MessageBus.Subscription`; call `unsubscribe()` on it when you
are done. An observer that keeps state for one workflow can extend
`AbstractWorkflowObserver` and be subscribed with `observeWorkflow(observer, eventType)`: it
receives only that workflow's events and unsubscribes itself when the workflow's
`WorkflowCompleteEvent` arrives.

Some events belong to no job: those of the dispatcher itself, the heartbeat events, and a
limiter change no job caused. They carry no snapshot, so they reach only subscriptions that
name no job type and no workflow.

## What an observer costs a job

Nothing a job waits for. Publishing puts the event on the queue of each matching subscriber
and returns at once. Every subscription has its own queue and its own virtual thread, so an
observer receives its events one at a time, in the order they were published, and a slow
one, say one that writes to a database, delays only itself. An observer that throws is
logged and goes on receiving. Subscribing the same observer twice (two equal observers)
gives one subscription and a warning.

## Publishing events of your own

Inside a job, publish through the `JobContext` your `execute` method receives:
`context.publish("Reading the contract", 25)` sends a progress event and
`context.publishUserNotification(...)` a notification.
[Reporting progress from a job](../harness/PROGRESS_GUIDELINES.md) says what to report and
when. An application can also define an event type of its own by implementing `JobEvent`
directly and publishing it with `context.publish(event)`. It belongs to no family, so it
reaches the subscribers that ask for its class or for every `JobEvent`. `MessageCompleteEvent`,
which `ReactiveThinker` publishes at the end of each exchange in a chat, is built this way.

## How it works inside

The full event hierarchy, what every event carries and renders as its message, the delivery
rules and the tests that hold them are in [Inside events](EVENTS_INTERNALS.md), for those
working on the runtime itself.
