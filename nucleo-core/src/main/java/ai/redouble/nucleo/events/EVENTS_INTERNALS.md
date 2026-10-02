# Inside events

This page is for people working on the runtime itself: the full category hierarchy, the rules
the event classes keep, what every event carries and renders, and the tests that hold each
rule. To use events, read [the guide](PACKAGE.md) first.

## Category hierarchy

Every framework-managed event belongs to exactly one sealed category. Broad subscribers pick categories; within a category, pattern matching on sealed subtypes is exhaustive.

```
JobEvent (unsealed contract - framework categories below, plus room for app-specific events)
├── LifecycleEvent (sealed) - platform-fired job state transitions
│   ├── JobScheduled (final)
│   ├── JobStartedEvent (final)
│   ├── TerminalEvent (sealed) - permits the base class and the two verdict markers
│   │   ├── AbstractTerminalEvent (sealed base of the four the dispatcher fires for a job it ran)
│   │   │   ├── JobCompletedEvent (final, also a SuccessEvent)
│   │   │   ├── JobFailedEvent, JobTimedOut (final, also FailureEvents)
│   │   │   └── JobCancelled (final)             - explicit stop, neither success nor failure
│   │   ├── SuccessEvent (sealed) - JobCompletedEvent
│   │   └── FailureEvent (sealed) - JobFailedEvent, JobTimedOut,
│   │                               DependencyFailureEvent (final, outside the base class)
│   ├── OrchestratorIdleEvent (final)
│   └── OrchestratorResumedEvent (final)
│
├── WorkflowTerminationEvent (sealed) - workflow-scope cleanup signal (parallel to LifecycleEvent)
│   └── WorkflowCompleteEvent (final)
│
├── ProgressEvent (sealed) - user-visible progress / streaming updates
│   └── JobProgressEvent (non-sealed, parametric payload)
│       └── ContentStreamEvent (inherits ProgressEvent via parent)
│
├── RetryEvent (sealed, in retry/) - retry / recovery signals
│   ├── RateLimitRetryEvent (final) - 429 transparent retries
│   ├── TransientErrorRetryEvent (final) - 5xx fault AND 529 overload retries (the error details distinguish them)
│   ├── OutputTruncationRetryEvent (final)
│   └── ResponseCorrectionRetryEvent (final)
│
├── NotificationEvent (sealed) - discrete user notifications
│   └── UserNotificationEvent (final)
│
├── OperationalEvent (sealed) - high-volume operational telemetry
│   └── LimiterEvent (final record)
│
├── SystemEvent (sealed) - no JobSnapshot, framework-internal state
│   └── SchedulerEvent (final)
│
└── HeartbeatEvent (sealed, in heartbeat/) - the heartbeat control plane, no JobSnapshot
    ├── HeartbeatRequested, CancelHeartbeat (records) - the two requests a job publishes
    └── HeartbeatScheduled, HeartbeatRebound, HeartbeatFired, HeartbeatFireFailed (records) - the Heart's notifications
```

`JobEvent` itself is deliberately not sealed. The sealed categories close the framework's own event surface while leaving room for application-specific events to implement `JobEvent` directly. One ships: `MessageCompleteEvent`, the end of one exchange in a chat session, outside every category, so it reaches only subscribers that ask for it by type or subscribe to everything. Every other concrete event in the three packages is in exactly one category, and every concrete event is final except three: `JobProgressEvent` (so applications can add parametric progress payloads), `ContentStreamEvent`, and `MessageCompleteEvent`. `EventCategoryContractTest` pins the permits of every category, the membership of every event, the finality rule, the readable set and the seven `MsgType` values.

Two sibling packages carry their own tables: [heartbeat/EVENTS_HEARTBEAT_INTERNALS.md](heartbeat/EVENTS_HEARTBEAT_INTERNALS.md) and [retry/EVENTS_RETRY_INTERNALS.md](retry/EVENTS_RETRY_INTERNALS.md).

## Orthogonal markers

- **`HumanReadable`** - the event carries a `getHumanMessage()` intended for a person. Implemented by the progress events (`JobProgressEvent`, `ContentStreamEvent`), the four retry events, `UserNotificationEvent`, `MessageCompleteEvent`, `WorkflowCompleteEvent` and the two orchestrator transitions. Scheduling, start, the terminal events, the scheduler event, the limiter event and the heartbeat events are telemetry and do not carry it. Use it to filter "anything a user can read" regardless of category.
- **`WorkflowTerminationEvent`** - signals to workflow-scoped observers that the workflow has ended and they can self-clean. Parallel to `LifecycleEvent`, not nested under it; `WorkflowCompleteEvent`, its one member, carries the outcome and the reason.

## Event structure

`MsgType` is the seven-valued type a person-facing event answers from `msgType()`: the four lifecycle phases `STARTING`, `PROGRESSING`, `COMPLETING`, `FAILING`, and the three general-purpose values `STATUS_UPDATE`, `MESSAGE`, `ERROR`. The table below says which event answers which.

All events expose:
- `JobSnapshot snapshot()` - immutable job state at event time (workflowId, jobId, displayName, action, timestamps). Null for every `SystemEvent` and every `HeartbeatEvent`, and for a `LimiterEvent` whose transition has no owning job; every other event carries the snapshot it was built with. An event with no snapshot reaches global and by-type subscriptions, never a job-type-scoped or a workflow-scoped one.
- `Instant timestamp()` - the instant the event was built.
- `String message()` - the event's log line, built from what it carries (the table below).
- Event-specific fields.

All events are immutable: no event exposes a public mutator, and what varies is given at construction. Can be published from any thread, delivered asynchronously to observers on dedicated virtual threads.

## What each event carries and says

| Event | Carries | `message()` | `msgType()` / `title()` |
|---|---|---|---|
| `JobScheduled` | state (`QUEUED` from the dispatcher, with the requirements and priority; the one-argument form is `ACCEPTED` with no requirements and priority 0) | none | - |
| `JobStartedEvent` | the attempt | `Job started (attempt <n>)` | - |
| `JobCompletedEvent<R>` | the result, as `getResult()` (empty for a null result) and `result()` | `Job completed successfully (attempt <n>)` | - |
| `JobFailedEvent` | the error (`getError`, also the terminal `getCause`) | `Job failed: <error message>`, or `unknown error` without one | - |
| `JobTimedOut` | the budget; `getError()` is null, a timeout has no exception | `Job timed out after <s> seconds` | - |
| `JobCancelled` | whether it was forced; a second constructor takes the message, for a publisher that states it at construction | `Job cancelled` or `Job forcefully cancelled`, or the publisher's message: the dispatcher builds a queued job's cancellation with the remover's, `Cancelled while queued: <reason>` from a cancel by id, `Workflow cancelled: <reason>` from a workflow cancel, `Cancelled by shutdown` at shutdown | - |
| `DependencyFailureEvent` | the error; a failure outside `AbstractTerminalEvent`, so the interface defaults: no responses, no metadata, timing from the snapshot | `Job failed due to dependency failure: <class>: <message>`, or `Unknown` | - |
| `JobProgressEvent<P>` | the payload and an optional percent; `hasProgress()` is true when a percent of zero or more was given | the payload's text form, null for a null payload | no percent `STATUS_UPDATE`, 0 `STARTING`, 1-99 `PROGRESSING`, 100 `COMPLETING`; the title is the job's display name |
| `ContentStreamEvent` | a `StreamChunk` (as `chunk()`) and the artifacts given with it, on any chunk, copied at construction and null when none | `Streaming chunk: <first fifty characters>`, `Streaming complete` on the last | `PROGRESSING` / `Streaming Content`, then `COMPLETING` / `Streaming Complete` at 100% on the last chunk; the human message is the chunk's content, else `Streaming...` or `Stream completed` |
| `UserNotificationEvent` | title, notification, severity, job state, optional details; the four factories read the state off the snapshot and carry no details | the notification | `ERROR` for an error, `COMPLETING` for a success, `MESSAGE` for a warning or an info; the title as given |
| `OrchestratorIdleEvent` | the exchange's LLM responses and metadata, as given | `Ready` | - |
| `OrchestratorResumedEvent` | nothing beyond the snapshot | `Processing` | - |
| `WorkflowCompleteEvent` | success and reason; the one-argument form is a success reading `Workflow completed successfully` | the reason | `COMPLETING` / `Workflow Complete` or `FAILING` / `Workflow Failed` |
| `MessageCompleteEvent` | success and an error description on failure | `Message processed` or `Message processing failed` | `COMPLETING` / `Message Complete` or `FAILING` / `Message Failed` |
| `SchedulerEvent` | a type (`STARTED`, `STOPPING`, `STOPPED`, `ERROR`) and, for an error, its cause | the type's default message unless a caller gave one; `Scheduler error: <message>` (or `Unknown`) for an error | - |
| `LimiterEvent` | one admission transition: account name and category, capacity, in-use, waiters, wait, reject reason, status indicator, amount | `<name> HELD (<inUse>/<capacity>, waiters=<n>)`, `<name> GRANTED (<inUse>/<capacity>)`, `<name> GRANTED after <ms>ms`, `<name> REJECTED (<reason>)`, `<name> RELEASED (<inUse>/<capacity>)` | - |

`EventMessageContractTest` holds every row of this table; the queued job's cancellation message is held by `KernelExecutionContractTest.aJobTakenOutOfAQueueIsSettledAsCancelled` in `harness`, and the immutability by `EventCategoryContractTest.noEventExposesAPublicMutator`. The retry and heartbeat tables are in their own packages.

### The terminal family

`AbstractTerminalEvent` is the base of the four events the dispatcher fires for a job it ran: completion, failure, timeout and cancellation. Each carries the attempt count, the LLM responses and the metadata collected during the run (an empty list or map when none were given, never null), and the instant the terminal state was reached, which is `getCompletionTime()`; `getDuration()` runs from the snapshot's start to that instant and is zero for a job that never started. `getResult()` is present on a completion only; the error is read through `getError()` and `getCause()`. `getCause()` is the failure's error on a `FailureEvent` and empty otherwise, so a cancellation has none. `DependencyFailureEvent` is the one terminal event outside the base class: the job never ran.

## Delivery scope

A subscription to a sealed category receives every member of it and nothing outside it: a `LifecycleEvent` subscriber sees a start and a completion, never a progress event and never a `WorkflowCompleteEvent`, which is parallel to lifecycle. An event with no snapshot reaches a global subscription (`Job.class`, `JobEvent.class`, no workflow) and a by-type subscription for all jobs (`Job.class` and the event's type or category, which is how a `Stethoscope` hears every `HeartbeatEvent`), never a subscription scoped to a job type or to a workflow: those never see a `SchedulerEvent`, a heartbeat event or a job-less `LimiterEvent`. `EventDeliveryScopeTest` holds both rules on the real bus.

## Built-in observers

| Observer | Scope | Subscribes to | Purpose |
|----------|-------|---------------|---------|
| `EventLogger` | Global | `JobEvent` (excludes `OperationalEvent` in its predicate) | Color-coded console log of all job events |
| `SystemHealthReporter` | Global | `JobEvent` (filter: `LimiterEvent` + `LifecycleEvent`) | Sampled system snapshot: limiters sorted by utilisation with gradient bar and status column, running jobs by type, active workflows, queue depths plus the count of jobs parked in admission. Red/yellow row tinting for blocked or throttled limiters. For `token_bucket` limiters the `used:` column is the rolling-minute sum of `LimiterEvent.amount` over `GRANTED_*` events (true throughput against the per-minute capacity), since `inUse` for token buckets is bucket depletion and would read near-empty under sustained pressure. Other categories display `inUse` directly. |
| `StreamingLogObserver` | Per-workflow | `JobEvent` | Streaming console output with reasoning/tool boxes |
| `OpenTelemetryObserver` | Global | `JobEvent` | Translates events into OTel spans (optional dep) |
| `MicrometerObserver` | Global | `JobEvent` | Forwards events to a Micrometer registry (optional dep) |

A deployment that persists job runs adds its own `LifecycleEvent` subscriber; none ships with the runtime.
