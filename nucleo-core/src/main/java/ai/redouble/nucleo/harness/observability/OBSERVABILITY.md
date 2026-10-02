# Observability

[The guide](PACKAGE.md) shows what the runtime reports about itself and how to use it: the
log, the traces, the metrics, the cost ledger and the spend cap. This page is about why they
have the shape they have, and about the one stream the guide only touches: the events that
describe how jobs wait for, get and give back the limited things they run on.

## Facts first, renderings after

What happens to a job starts as a typed event on the message bus
([Events](../../events/PACKAGE.md)), and every rendering in this package, the event log line,
the span, the meter, the priced call, is an observer of those events. So a new way of looking
at the system is a new observer, and it sees exactly what the others see.

The observers of this package keep what they know in memory. A deployment that persists
every job and every LLM call does it with its own `LifecycleEvent` subscriber on top of these
events; the runtime ships none. Redouble's commercial platform, Silverlake, is one such
deployment, and documents its records with its recorder.

## Cost accounting in memory

`CostLedger` is the runtime's own cost accounting, for a host with no database of its own: it
prices every call a finished job made from the catalog, keeps the result per workflow, per
model and per call, and is the money wall of admission. The guide says what it prices and how
a cap judges a job; this section is why the rules have that shape.

Nothing is priced at a guess and currencies are never summed together, because a total that
estimates an unpriced call or adds two currencies is a number nobody can act on; for the same
reason a model with no price cannot be admitted under a cap at all. A failed job's calls
count, since a call that ran and failed spent its tokens. The cap judges what is in flight as
well as what was spent, because a fan-out of a thousand jobs admitted before the first one is
priced would otherwise pass any cap. And the cap refuses new work only: an orchestrator that
sees the refusal stops submitting, and whatever is mid-flight runs to its end, so nothing is
torn down half-done.

Staying under the cap is best effort in one bounded sense: a reservation is the runtime's
token count and the declared output, and the provider's invoice can differ from both per call.
[Why admission has this shape](../admission/ADMISSION.md), in "The money wall is a refusal,
never a stop", follows this through.

## Exporters are callbacks

Nucleo ships observers for the two standard platforms, OpenTelemetry for traces and
Micrometer for metrics, and both are optional dependencies: an application adds the library
it already uses and provides the SDK, exporter or registry behind it. Both follow the same
pattern:
a global `JobObserver` that delegates to a `@FunctionalInterface` customizer. The observer
does the part every deployment needs the same way. `OpenTelemetryObserver` keeps the span
lifecycle: it creates a span on `JobStartedEvent`, ends it on the `TerminalEvent`, sets
`ERROR` status and records the exception on failures, and maps job parent-child
relationships directly onto span parent-child relationships. The customizer receives every
event together with the platform's own object (the `Span` or the `MeterRegistry`) and decides
what is recorded, usually by pattern matching on the event type. `DefaultSpanCustomizer` and
`DefaultMeterCustomizer` are one choice of attributes and meters; a deployment that wants
others writes its own customizer, and a deployment on another platform writes a new observer.

## The limiter stream

A job runs only once it has been admitted with everything it declared: a slot on a model's
rate limit, a database connection, memory, a place under an external service's quota
([Admission](../admission/PACKAGE.md)). Admission emits one event per transition of every
account it grants, through the message bus. Every hold, every grant, every refusal and every
release surfaces as a typed `LimiterEvent` that `OpenTelemetryObserver`, `MicrometerObserver`,
and the dedicated `SystemHealthReporter` consume. Per-job attribution falls out of the stream
because each event carries the `JobSnapshot` of the job whose demand is being evaluated: the
snapshot travels with the waiter and the grant, so an event emitted on the evaluator thread or
on the timeout executor is attributed exactly as one emitted on the job's own thread.

### Why one event per transition

An aggregated approach, one summary per limiter per interval fed by counters on the hot path,
gives you population statistics but loses per-job lineage. It answers "what is happening" but
not "why is **this specific job** stalled". Per-job attribution lets a dashboard answer "which
jobs are currently stuck on which limiter" by walking the event stream - a question
aggregation cannot answer at any latency finer than the aggregation window. That question is
what the stream is for.

At the volumes this platform runs at (tens of thousands of parallel jobs, each touching 0-3
limiters, ~2K-18K events/sec peak on the message bus), per-transition events are affordable on
the `LinkedQueueMessageBus`:
- Hot-path cost per emit: one record allocation (~200 bytes), one volatile read for the bus,
  one fan-out to matching subscribers.
- At 18K events/sec peak with ~3 matching subscribers: ~54K enqueues/sec, ~5 MB/sec young-gen
  garbage. Trivial on modern GC, well under the single-core budget.

Per-transition events fit inside the message bus's measured envelope, so nothing is traded for
that answer.

### A family of its own, so the volume stays out of the way

`LimiterEvent` implements the sealed `OperationalEvent` category (parallel to
`LifecycleEvent`, `RetryEvent`, etc.). Broad subscribers that do not want the volume never see
`OperationalEvent`: a persistence recorder subscribes by category (`LifecycleEvent.class`) and
`EventLogger` excludes `OperationalEvent` in its predicate. Subscribers that want it subscribe
to `JobEvent.class` and pick it out: the OTel and Micrometer observers pattern-match on
`LimiterEvent` in their customizer, and `SystemHealthReporter` keeps `LimiterEvent` and
`LifecycleEvent` in its predicate. The sealed category is the volume firewall; no marker
interface is needed.

### What one event says

```java
public record LimiterEvent(
    JobSnapshot snapshot,       // nullable; null when the transition has no owning job
    Instant timestamp,
    String limiterName,         // "memory", "PubMed", "epo:search", "anthropic-sonnet-4-6"
    String limiterCategory,     // "memory" | "token_bucket" | "elastic_window" | "semaphore"
    long capacity,              // constant per limiter, carried so every event is self-describing
    long inUse,                 // post-transition level
    int waiters,                // post-transition count
    Type type,                  // HELD | GRANTED_IMMEDIATE | GRANTED_FROM_HOLD | REJECTED | RELEASED
    long waitNanos,             // 0 except on GRANTED_FROM_HOLD and REJECTED-after-HELD
    String rejectReason,        // null unless REJECTED; conventional vocabulary (see below)
    String statusIndicator,     // null when nominal; "blocked", "probing", "throttle:2.0x", "pace:1500ms"
    long amount                 // units of capacity moved by this transition (1 for unit-typed limiters)
) implements OperationalEvent {
    public enum Type { HELD, GRANTED_IMMEDIATE, GRANTED_FROM_HOLD, REJECTED, RELEASED }
}
```

Every event is self-describing: a late-joining listener can start tracking any limiter from
the first event it sees without a bootstrap snapshot call. `capacity` is redundant on the wire
(constant per limiter) but removes any need for a separate "limiter registered" event.
`statusIndicator` is populated by the limiter at emission time from its `statusIndicator()`
override and exposes abnormal operational state that the numeric columns alone do not reveal -
a circuit-open elastic window shows `capacity=10, inUse=0, waiters=0` but is refusing every
demand; the indicator is how consumers see that.

### Why a job was refused

`rejectReason` is a plain String, so framework extensions can invent new reasons
without touching core. Broad subscribers should tag metrics on it with a label cap to bound
cardinality. Values emitted by `Admission`, the one publisher of the stream:

| Value | Meaning |
|---|---|
| `cancelled` | The job was cancelled or its thread interrupted while parked in admission |
| `shutdown` | Admission stopped while the job was parked: the dispatcher is shutting down |
| `circuit_blocked` | An account refused the job with an LLM-readable message: a circuit open or probing, an endpoint closed, an amount that can never fit the account's capacity |
| `aborted` | An account threw something that is not LLM-readable while answering about the job, or admission ended on a defect while the job was parked; the cause is logged with its stack trace |

### The sequences one admission produces

One admission produces, per account in the job's demand, one of these sequences:

| Outcome on that account | Event sequence |
|---|---|
| Fit at every look | `GRANTED_IMMEDIATE` |
| Short at some pass, granted later | `HELD` -> `GRANTED_FROM_HOLD(waitNanos)` |
| Short, then the job cancelled or interrupted | `HELD` -> `REJECTED(waitNanos, "cancelled")` |
| Short, then admission stopped | `HELD` -> `REJECTED(waitNanos, "shutdown")` |
| Refused by this account | `REJECTED(0, reason)` when the account had never held the job, else `HELD` -> `REJECTED(waitNanos, reason)` |
| Held on this account when another account refused | `HELD` -> `REJECTED(waitNanos, reason)` with the refusing account's reason |

`HELD` is emitted at most once per account per admission, on the first pass where the account
was short. A demand short on three accounts produces three `HELD` events and, at the grant,
three `GRANTED_FROM_HOLD` events with three different `waitNanos`, because the accounts were
short over different intervals. `RELEASED` is emitted once per account returned: the `RELEASE`
accounts on release, every account on rollback. Memory appears like any other account,
because it is one: admission emits `HELD` on `memory` for a job it parks, whether the critical
latch is set or the throttle spacing since the last release has not elapsed, and
`GRANTED_FROM_HOLD` when that job is finally released. The gate itself emits nothing. Nothing
emits on a `fits` that answers false, so the stream carries transitions only.

### One snapshot on the console

`SystemHealthReporter` turns the stream into one periodic snapshot and prints no line per
transition: a line on every HELD, REJECTED or GRANTED_FROM_HOLD would flood the console at peak
throughput. The snapshot fires often enough to catch transient pressure, and an abnormal
limiter surfaces through its status column and the tint of its row.

## How it works inside

How the snapshot travels with a waiter, how accounts name themselves, the base classes every
account builds on, the EPO router, the health snapshot section by section, how the default
customizers map limiter events onto spans and meters, and the concurrency invariants of the
elastic window are in [Inside observability](HARNESS_OBSERVABILITY_INTERNALS.md), for those
working on the runtime itself.
