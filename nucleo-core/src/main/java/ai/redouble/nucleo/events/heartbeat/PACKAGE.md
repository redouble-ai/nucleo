# Package: ai.redouble.nucleo.events.heartbeat

Some work belongs later, or again and again: a digest every morning, a look at an open case
every hour, an agent that decides at the end of each run when it wants to look again. A
thread that sleeps until then holds its memory for nothing, and a timer of your own would
start work outside the rules every other job runs under: who it runs for, what it may touch,
what it may spend.

In Nucleo such work is a heartbeat: a request, published as an event on the message bus
([the page on events](../PACKAGE.md) explains both), to submit a given job at a given time, once or
at a fixed interval. The runtime's one scheduler, the Heart, keeps the requests in a store
and, when one is due, submits the job as an ordinary job, for the same user and inside the
same scope as the job that asked. Between runs nothing is held but the stored request. This
package holds the events of that exchange: the two requests a job can publish and the four
notifications with which the Heart reports what it did. Who may schedule, how a fired job
gets its identity and how a conversation carries across runs are on
[the job runtime page](../../harness/PACKAGE.md), under "Aliveness Without Threads:
Heartbeats".

## Asking for a heartbeat

An orchestrator, a thinker or a doer, schedules by publishing a `ScheduleHeartbeat` through
its `JobContext`. It names the job class to fire, an id of your choosing for the schedule,
the instant of the first run, and optionally the job's input, a conversation to continue and
a `Recurrence.FixedInterval` to repeat at. The context turns it into a `HeartbeatRequested`
that carries the publisher's user and scope. A job that is no orchestrator is refused:
scheduling is submitting work later, and only orchestrators submit work. Code outside any
job, such as the application's startup, schedules with
`JobDispatcher.scheduleHeartbeat(spec, userId)`, naming the user the work runs for.

A schedule published again under an id already in use replaces the earlier one. Any job
removes a schedule by publishing a `CancelHeartbeat` with its id; a run that has already
fired is left to finish.

The runtime keeps schedules in memory, so a restart forgets them: fixed schedules are
published again by startup code, and a deployment whose schedules must survive a restart
plugs in a durable `HeartbeatStore`.

## What the Heart reports

| Event | What happened |
|---|---|
| `HeartbeatRequested` | A job asked for a heartbeat. |
| `CancelHeartbeat` | A job asked to remove one. |
| `HeartbeatScheduled` | The request is in the store. |
| `HeartbeatRebound` | The request reused an id and replaced an earlier schedule; the event carries both. |
| `HeartbeatFired` | A due heartbeat was submitted; the event carries the id of the job it started. |
| `HeartbeatFireFailed` | Submitting the job threw; the event carries the failure. A recurring heartbeat's next run is scheduled all the same. |

## Watching heartbeats

A heartbeat event belongs to no job, so its snapshot is null and it reaches only
subscriptions that name no job type and no workflow: subscribe to `HeartbeatEvent` for all
jobs. To follow what a fired run then did, take the job id from `HeartbeatFired`; the run's
own events carry it in their snapshot.

For an audit log or a dashboard, extend `Stethoscope`. It receives every heartbeat event and
hands each to a method of its own, `onScheduleRequested`, `onScheduled`, `onCancelled`,
`onRebound`, `onFired` and `onFireFailed`, so you override the moments you care about.
`attach(dispatcher)` subscribes it and `detach()` removes it. Like every observer it runs on
its own queue, so a slow audit sink delays only itself.

The heartbeat events are few, and each is worth keeping, so they form a family of their own,
apart from the high-volume `OperationalEvent` family that broad subscribers leave out. The
event logger writes them at INFO ([Logs, traces, metrics and cost](../../harness/observability/PACKAGE.md)).

## How it works inside

What each event carries and renders as its message, and the tests that hold them, are in
[Inside heartbeat events](EVENTS_HEARTBEAT_INTERNALS.md), for those working on the runtime
itself.
