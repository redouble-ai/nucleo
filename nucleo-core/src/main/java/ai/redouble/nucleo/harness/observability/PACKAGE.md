# Package: ai.redouble.nucleo.harness.observability

The events of [the previous pages](../../events/PACKAGE.md) are raw facts: this job started,
that one failed, this model call returned so many tokens. What operators work with is
different: log lines they can read and filter, traces that show where a slow request spent
its time, metrics on a dashboard, and what a run cost. This package turns the one into the
other. Each of its classes is an observer, subscribed to the dispatcher's message bus like an
observer of your own, and like every observer it runs on its own queue, so none of them
slows a job.

The observers here are:

- `EventLogger`, a log line for every event, on loggers you can tune per job and per kind of
  event;
- `StreamingLogObserver`, one workflow's streamed text and progress on the console;
- `SystemHealthReporter`, a periodic picture of the whole runtime: limits, memory, running
  jobs, queues;
- `OpenTelemetryObserver`, a trace span for every job;
- `MicrometerObserver`, metrics;
- `CostLedger`, the price of every model call per workflow, and a cap on what a workflow may
  spend.

## What runs without code of yours

In a Spring Boot or a Quarkus application, the integration subscribes two of them when the
dispatcher starts: an `EventLogger`, so every job shows up in the application's log, and a
`CostLedger`, which it also registers as the dispatcher's spend gate so caps are enforced.
Its `NucleoRuntime` bean hands you both, through `dispatcher()` and `ledger()`
([Spring Boot](../../../../../../../../../nucleo-spring-boot-starter/src/main/java/ai/redouble/nucleo/spring/PACKAGE.md),
[Quarkus](../../../../../../../../../nucleo-quarkus/src/main/java/ai/redouble/nucleo/quarkus/PACKAGE.md)).
The other observers you subscribe yourself, where the application starts.

A plain Java program subscribes whatever it wants after starting the dispatcher:

```java
JobDispatcher dispatcher = JobDispatcher.getInstance();
dispatcher.start();
dispatcher.subscribe(new EventLogger(), JobEvent.class);
dispatcher.subscribe(new SystemHealthReporter(), JobEvent.class);
```

All `EventLogger` instances are equal, so subscribing a second one where the integration has
already subscribed the first changes nothing, apart from a warning in the log.

## Logging

`EventLogger` writes one line per event: the workflow id, the job id and its state, then the
event's message, colored so that one workflow's lines stand out from another's. A failure
is followed by its stack trace, unless its error is one written for the model to read or a
child job's failure whose trace that child's own line already printed. It leaves
out the limiter events, which come by the thousand; `SystemHealthReporter` summarizes those.

Each line goes to the logger of the job it is about, named after the job's class plus one
segment for the kind of event, and each kind has its level:

| Segment | Event | Level |
|---|---|---|
| `failure` | `FailureEvent` | WARN |
| `lifecycle` | any other `LifecycleEvent` | INFO |
| `retry` | `RetryEvent` | DEBUG |
| `workflow` | `WorkflowTerminationEvent` | INFO |
| `notification` | `UserNotificationEvent` | INFO and SUCCESS at DEBUG, WARNING at WARN, ERROR at ERROR |
| `stream` | `ContentStreamEvent` | DEBUG |
| `progress` | any other `ProgressEvent` | DEBUG |
| `system` | `SystemEvent` | INFO |
| `heartbeat` | `HeartbeatEvent` | INFO |
| `event` | any other event | INFO |

So at the usual INFO level the log shows every job starting and ending, and every failure;
the steps inside a run, its progress, the tools a thinker starts and the retries, are at
DEBUG. You choose what you see the way you do for any logger. Set the demo's agent,
`ai.redouble.demo.DemoAgent`, to DEBUG and you see all of its own lines, the tools it starts
and completes among them; set `ai.redouble.demo.DemoAgent.progress` alone and you see its
progress lines only; set a package and it covers every job class under it. The model calls
and the tools an agent runs are jobs of their own and log under their own classes: a model
call under `ai.redouble.nucleo.tools.thinking.LLMCall`, so its retries arrive on
`ai.redouble.nucleo.tools.thinking.LLMCall.retry`. Events that belong to no job log under the
event's own class plus the segment.

While a line is written, the logging context (the MDC) holds the event's workflow id under
the key `workflowId` (`EventLogger.MDC_WORKFLOW_ID`). A logging backend can use it to print
the workflow on every line, or to lower the level for one workflow alone, as Logback's
`DynamicThresholdFilter` does, while every other workflow stays at the configured level.

## Watching one workflow on the console

`StreamingLogObserver` prints what one workflow's jobs stream and report: the text a model
streams, printed as it arrives, and each progress report as `Progress: N%` followed by its
message. It is meant for development, where you watch one run in a terminal:

```java
Identifiable workflow = Job.workflow("you", "orders");
dispatcher.observeWorkflow(new StreamingLogObserver<>(workflow.getWorkflowId()), JobEvent.class);
```

It unsubscribes itself when the workflow completes. Another constructor takes an inactivity
timeout, after which the bus removes it, and two flags that turn the streamed text and the
progress lines on and off.

## The state of the whole runtime

`SystemHealthReporter` prints a block every fifty events it receives. It lists every limit
jobs are admitted under ([Admission](../admission/PACKAGE.md)), fullest first, with how much
of it is in use and how many jobs wait on it; a limit that is refusing work or has slowed
itself down is marked and tinted. Then the memory gate's state, the running jobs in total and
by kind (thinkers, tools, model calls and the rest), the workflows with a job running, and the
dispatcher's queues with the number of jobs waiting in admission. It answers "why is nothing
moving" at a glance: a limit at 100% with a crowd of waiters is where the jobs are.

## Traces

`OpenTelemetryObserver` gives every job a span. The span opens when the job starts, closes
when it ends, and hangs under the span of the job that submitted it, so an agent's trace
shows each model call and each tool call inside it, with its duration. A failed job's span
has status `ERROR` and the exception recorded. It needs `io.opentelemetry:opentelemetry-api`
on the classpath, which nucleo-core leaves optional, and an OpenTelemetry SDK and exporter
set up by the application:

```java
Tracer tracer = openTelemetry.getTracer("my-app");
dispatcher.subscribe(new OpenTelemetryObserver(tracer, new DefaultSpanCustomizer()), JobEvent.class);
```

What goes on a span is decided by the `SpanCustomizer` you pass, which receives each event of
a job together with its open span. `DefaultSpanCustomizer` sets the job's identity when it
starts (`job.id`, `job.parent_id`, `workflow.id`, `user.id`, `job.type`, `job.display_name`,
`job.action`, `job.attempt`), adds a span event for each progress message and for each
limiter change while the job runs, and at the end sets the attempts, the duration and the job's model use
(`llm.calls`, `llm.input_tokens`, `llm.output_tokens`, `llm.cache_read_tokens`,
`llm.cache_creation_tokens`, `llm.latency_ms`, `llm.models`). To record something else, write
your own customizer: it is one method, `customize(event, span)`, usually a `switch` on the
event.

## Metrics

`MicrometerObserver` hands every event, with a Micrometer `MeterRegistry`, to a
`MeterCustomizer` that decides what to record. It needs `io.micrometer:micrometer-core`, also
optional in nucleo-core, and a registry from the application (Prometheus, Datadog or any
other):

```java
dispatcher.subscribe(new MicrometerObserver(registry, new DefaultMeterCustomizer()), JobEvent.class);
```

`DefaultMeterCustomizer` records:

- `nucleo.job.started` and `nucleo.job.completed`, counters tagged by job type;
  `nucleo.job.failed`, tagged by job type and error class, where a timeout counts with the
  class `timeout` and a cancellation with `cancelled`; `nucleo.job.duration`, a timer tagged
  by job type and outcome;
- `nucleo.llm.calls`, `nucleo.llm.input_tokens`, `nucleo.llm.output_tokens`,
  `nucleo.llm.cache_read_tokens` and `nucleo.llm.cache_creation_tokens`, counters tagged by
  model, and `nucleo.llm.latency`, a timer tagged by model;
- `nucleo.limiter.acquire`, a counter of admissions tagged by limit, category and whether the
  job had to wait; `nucleo.limiter.wait`, a timer of how long it waited; and
  `nucleo.limiter.reject`, a counter of refusals tagged by limit, category and reason.

No metric is tagged with a job id, since there would be one series per job. The job id is on
the trace spans.

## What a run costs

`CostLedger` prices every model call a finished job made, using the prices in the model
catalog ([The catalog, grades and the picker](../models/PACKAGE.md)), and keeps the result
per workflow, per model and per call. A failed job's calls count too, since a call that ran
and failed still used its tokens. Once subscribed, it answers while the work runs:

- `spent(workflowId, "USD")` is what the workflow has spent so far in that currency, as a
  `Cost`;
- `workflow(workflowId)` is the whole account of it: the totals per currency, one
  `ModelSpend` per model with its calls, tokens, latency and cost, and every call. It is null
  until a job of the workflow has finished a model call.

A `Cost` is an amount and a currency, and Nucleo never converts between currencies: a
deployment that pays one provider in dollars and another in euros sees two totals. A call on
a model the catalog gives no price is counted with its tokens and marked unpriced, and never
priced at a guess.

## Capping what a workflow spends

A workflow is capped with `cap(workflowId, cost)` on a ledger registered as the dispatcher's
spend gate (`registerSpendGate`, which the Spring Boot and Quarkus integrations do for you).
From then on, before each job of that workflow is given anything, the ledger adds up what the
workflow has spent, what its running jobs have reserved, and the most the new job's model
calls can cost. When the sum would pass the cap, the job is refused with a
`SpendCapExceededException` whose message names the cap, the spend, what is in flight and the
reservation. Counting what is in flight is what stops a fan-out of a hundred jobs from all
being admitted before the first one is priced.

A cap stops new work only: jobs already running finish. Under a cap, a job whose model has no
price, or a price in a currency the workflow has no cap for, is refused as well, since a spend
nobody can state cannot be held to a cap. `uncap` lifts the caps. Staying under the cap is as
exact as the reservations: the provider's invoice can differ from the runtime's count of each
call by a little, as [the design essay](OBSERVABILITY.md) explains.

## Recording runs for good

Every observer here keeps what it knows in memory. A deployment that needs a record of every
job and every model call subscribes its own observer to `LifecycleEvent` and writes the
terminal events, with the model responses they carry, to its own store. None ships with the
runtime. Heartbeats have an observer base of their own, `Stethoscope`
([Heartbeats](../../events/heartbeat/PACKAGE.md)).

> **Example:** [A dollar cap on a workflow](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/cap/PACKAGE.md) -
> a ledger subscribed and registered as the spend gate, a cap of one cent, and the run it
> refuses at admission.

## How it works inside

The exact rules of each observer, of `Cost` and of the ledger, with the tests that hold
them, and the inner workings of the limiter stream are in
[Inside observability](HARNESS_OBSERVABILITY_INTERNALS.md), for those working on the runtime
itself.
