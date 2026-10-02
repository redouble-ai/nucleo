# Inside heartbeat events

This page is for people working on the runtime itself: why the heartbeat events are a
category of their own, what each one carries and renders as its message, and the tests that
hold them. To schedule and watch heartbeats, read [the guide](PACKAGE.md) first.

## The category

`HeartbeatEvent` is sealed to six members. It is deliberately not `OperationalEvent`: that
category is high-volume telemetry broad subscribers skip, while these are low-volume commands
and audit-worthy transitions dashboards and persisters subscribe to. Every member answers a
null snapshot, because a heartbeat transition has no owning job; an event with no snapshot
reaches global and by-type subscriptions (a `Stethoscope` subscribes to `HeartbeatEvent` for
all jobs), never a job-type-scoped or a workflow-scoped one. The fired
job's own lifecycle events carry its snapshot; the correlation from a schedule to its runs is
the `jobId` on `HeartbeatFired`.

| Event | Published by | Carries | Message |
|---|---|---|---|
| `HeartbeatRequested` | the job context, for a `ScheduleHeartbeat` an orchestrator published | the minted `Heartbeat`; refuses to exist without one, since the heartbeat is what carries the captured authority | `Heartbeat requested: <heartbeat>` |
| `CancelHeartbeat` | any job, through its context | the `heartbeatId` to remove; a null or blank id is refused, there is no cancel-all; an in-flight fired job is untouched | `Heartbeat cancel requested: <id>` |
| `HeartbeatScheduled` | the Heart, when a request landed in the store | the heartbeat | `Heartbeat scheduled: <heartbeat>` |
| `HeartbeatRebound` | the Heart, when a request re-used an existing id: most recent wins | the displaced spec and the current one | `Heartbeat rebound: <id> (was <displaced>, now <current>)` |
| `HeartbeatFired` | the Heart, when a due heartbeat was dispatched | the heartbeat and the run's `jobId` | `Heartbeat fired: <id> -> job <jobId>` |
| `HeartbeatFireFailed` | the Heart, when submission threw | the heartbeat and the failure; a recurring entry's next instance is enqueued regardless | `Heartbeat fire failed: <id> - <failure>` |

Each event is stamped with its construction instant, and each carries the instant explicitly
so a record built from a stored transition keeps its own.

`HeartbeatEventContractTest` holds the two requests; `HeartbeatDispatchTest` in
`harness` drives the four notifications through the Heart and pins their messages.
