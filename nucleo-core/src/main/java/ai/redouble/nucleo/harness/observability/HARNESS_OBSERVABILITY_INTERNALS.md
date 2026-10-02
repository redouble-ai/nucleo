# Inside observability

This page is for people working on the runtime itself: every rule the observers, the cost
types and the ledger keep, with the tests that hold each one, and the inner workings of the
limiter stream. To use them, read [the guide](PACKAGE.md) first, and
[the design essay](OBSERVABILITY.md) for why they have this shape.

## What is here

| Type | Role |
|---|---|
| `JobObserver<T>` | The subscriber contract: a predicate consulted once at subscription, `observe`, and `isStale` for the bus's cleanup. |
| `Cost` | An amount in one ISO 4217 currency, exact to a billionth of its unit; never converted, never summed across currencies. |
| `CostLedger` | In-memory cost accounting per workflow, model and call, and the `SpendGate` that caps what a workflow may commit. |
| `EventLogger` | One colored console line per job event, operational events excluded, on the job's logger plus the kind of event, at that kind's level. |
| `StreamingLogObserver` | Per-workflow console rendering of streaming chunks and progress lines. |
| `SystemHealthReporter` | A sampled system snapshot: limiters, memory, running jobs, workflows, queues. |
| `Stethoscope` | The base for observers of the heart's six heartbeat events. |
| `OpenTelemetryObserver`, `SpanCustomizer`, `DefaultSpanCustomizer` | One span per job, ended on its terminal event, decorated by a callback (optional `opentelemetry-api`). |
| `MicrometerObserver`, `MeterCustomizer`, `DefaultMeterCustomizer` | Every event handed to a callback that records meters (optional `micrometer-core`). |

## The observer contract

`JobObserverContractTest` holds this section.

`getPredicate()` answers null by default, which the bus reads as "every event of the
subscribed type"; it is consulted once, at subscription. `observe` is the one abstract method;
it runs on the observer's own virtual thread and owns its exceptions. `isStale()` answers false
by default; the bus calls it during its cleanup sweep and unsubscribes an observer that answers
true, after which that observer receives nothing more (`harness/MessageBusContractTest`,
`staleSubscriptionsAreReaped_andStopReceiving`, holds the reaping; the contract test here
drives the sweep on a bus of its own). Two observers that are equal are one subscription: the
bus answers a second subscription of an equal observer with a no-op subscription and a warning.

## Cost

`CostLedgerTest` holds this section.

A `Cost` is an amount and a three-letter upper-case currency; any other currency is refused at
construction. The amount is rounded to a billionth of its unit, so a thousand summed
thousandths read as exactly one. `plus` and `exceeds` refuse a different currency, and
`exceeds` is strictly past the cap: an amount equal to the cap is not past it.
`Cost.of(spec, input, cacheWrite, cacheRead, output)` prices a call on a priced entry: the
uncached input at the input price, cache writes and reads at the entry's effective cache
multipliers, output at the output price, an embeddings entry pricing input alone; it is null on
an unpriced entry, never zero. `Cost.reserved(spec, input, output)` is the most a reservation
can cost, every token at its own price and none of it cached, null on an unpriced entry.
`toString` renders six decimals and the currency.

## The ledger

`CostLedgerTest` holds this section.

The ledger observes terminal events only. For every response a finished job carries, failed
jobs included, it records one `Call` under the job's workflow: the model as the catalog names
it, the served model, the four token counts, the latency and the cost. A response on an entry
the catalog does not price is counted unpriced; one on a model the catalog does not know is
counted under the id the response named, with a warning; one that named no model is counted
under `(unknown)`. A workflow's `Workflow` view sums per currency (`getTotals`, `getTotal`),
splits per model (`ModelSpend`: calls, tokens, latency, cost, unpriced calls) and lists every
call; `workflow(id)` is null until a finished job of the workflow has carried at least one
model response, and `spent(id, currency)` is zero of that currency until a priced call ran in
it.

## The cap

`CostLedgerTest` holds this section; `harness/admission/PACKAGE.md` states where the gate sits.

`cap(workflowId, cost)` caps what the workflow may commit in that currency from then on; a
workflow holds one cap per currency, `caps(workflowId)` lists them and `uncap` removes them
all. The ledger never lowers a cap. As the dispatcher's `SpendGate` the ledger is consulted
after an attempt's bindings are priced and before any resource is acquired
(`harness/KernelExecutionContractTest`,
`modelsAreResolvedAndPricedBeforeAcquisition_andTheSpendGatesJudgeLast`, holds the placement).
A refusal names the cap, the spend so far, the commitments in flight and the reservation. Under a cap, a
job is refused with `SpendCapExceededException` when its reservation plus the workflow's
spend plus what its admitted, unfinished siblings have committed (`committed(workflowId,
currency)`) would exceed the cap; when its model is priced in a currency the workflow holds
no cap for; or when its model is not priced at all. Each currency is judged against its own
cap. An admitted reservation stays committed until the job's terminal event. A refusal never
touches a running job; a workflow under no cap is not gated.

## The event logger

`EventLoggerTest` holds this section.

The predicate excludes `OperationalEvent`s, which `SystemHealthReporter` renders. Every other
event becomes one line on the logger of the class it is about - the job's own class, read off
the snapshot - plus one segment naming the kind of event, at that kind's fixed level:

| Segment | Event | Level |
|---|---|---|
| `failure` | `FailureEvent` | WARN |
| `lifecycle` | any other `LifecycleEvent` | INFO |
| `retry` | `RetryEvent` | DEBUG |
| `workflow` | `WorkflowTerminationEvent` | INFO |
| `notification` | `UserNotificationEvent` | its severity: INFO and SUCCESS at DEBUG, WARNING at WARN, ERROR at ERROR |
| `stream` | `ContentStreamEvent` | DEBUG |
| `progress` | any other `ProgressEvent` | DEBUG |
| `system` | `SystemEvent` | INFO |
| `heartbeat` | `HeartbeatEvent` | INFO |
| `event` | any other event | INFO |

A layout that prints the logger therefore names the job and the kind of line, and a
deployment sets levels the way it does for any other logger: the job's class for all of its
events, the class plus a segment for one kind (`ai.redouble.demo.DemoAgent.progress=DEBUG`),
a package for every job under it. An event with no snapshot, and a snapshot that came back
from JSON without its `jobClass`, log under the event's class plus its segment; the
observer's own faults log under `EventLogger`. While an event's lines are written, the MDC
holds its workflow id under `EventLogger.MDC_WORKFLOW_ID` (`workflowId`), so a backend can
lower the threshold of one workflow alone - in Logback, a `DynamicThresholdFilter` keyed on it.

An event with no job snapshot reads `[SYSTEM] ` and its message; a job event carries the
workflow id shortened to its first
ten and last four characters past fifteen, then `[jobId/STATE]`, then the message, in ANSI
colors chosen from the workflow and job ids. A failure whose error is neither LLM-readable nor
an `ExecutionException` wrapping a child's failure is followed by the error and its stack
trace at ERROR; an LLM-readable failure gets no trace. All instances are equal, so a bus holds
one.

## The streaming log

`StreamingLogObserverTest` holds this section.

A per-workflow observer of `JobProgressEvent`s; every other event is ignored. A
`StreamChunk` payload, when chunks are logged, prints to standard output: inline for ordinary
content, in a double-lined box for content starting with `[REASONING`, in a single-lined box
for `[TOOLS TO EXECUTE`, on a check-marked line for `[TOOL COMPLETED`; a box wraps its content
at word boundaries within its width; the last chunk ends the line and logs
`STREAMING COMPLETE`. A progress percentage, when status is logged, logs
`Progress: N%` with a non-chunk payload appended; a plain string payload without a
percentage is logged as it is. Two observers are equal when they watch the same workflow with
the same two flags and both have or lack an inactivity timeout. The workflow filter and the
inactivity staleness come from `AbstractWorkflowObserver` in `harness`.

## The health reporter

`SystemHealthReporterTest` holds this section.

Subscribed to `JobEvent` and filtered to `LimiterEvent` and `LifecycleEvent`. Every fiftieth
event it receives, it logs one snapshot block: limiter rows sorted by utilisation, one per
limiter name it has seen, with the name cut to 28 characters, the percentage, the waiter
count, used and total in 1000-based short form (`1.8M`, `7.9G`), the category's unit (`B`,
`tok`, `req`, `slot`) and the status indicator when there is one, the row tinted red for
`blocked` and yellow for `throttle`, `pace` and `probing`; for a `token_bucket` the used
figure is the sum of `amount` over the last minute's `GRANTED_*` events, for every other
category the event's `inUse`, and a CPU row with the process and system load follows when
the JVM exposes them; then the memory gate's status summary; then the running jobs,
in total and by type, where a job counts once from its first `JobStartedEvent` to its terminal
event whatever retries republish; then the count of workflows with a running job; then the
dispatcher's standard and fast queue sizes and the jobs parked in admission.

## The stethoscope

`StethoscopeTest` holds this section.

A `Stethoscope` accepts every `HeartbeatEvent` and routes each of the six to its hook;
a hook not overridden does nothing. `attach(dispatcher)` subscribes it once and refuses a
second attach with `IllegalStateException`; `detach()` unsubscribes and may be called again.

## The exporters

`OpenTelemetryObserverTest` and `MicrometerObserverTest` hold this section.

`OpenTelemetryObserver` opens one span per job, on the first `JobStartedEvent` that carries
its job id, named after the display name, else the job type, else the id, parented on the span
of the parent job when that span is active; a retry's republished start reaches the
`SpanCustomizer` on the existing span with its attempt number and opens nothing; the span ends
on the job's terminal event, with status `ERROR` and the recorded exception for a failure and
`OK` otherwise; every other event of a job with an active span goes to the `SpanCustomizer` on
that span, and events without a snapshot, a job id or an active span go nowhere. `DefaultSpanCustomizer` sets the identity
attributes on start, the attempt, duration and LLM totals on the terminal event, a span
event per progress message and a `limiter.<type>` span event per limiter transition that
reaches it. Admission precedes the start event, so the transitions that admit a job's first
attempt arrive before its span opens and go nowhere; the releases at the end of each attempt,
and the admissions of an attempt that runs again, land on the span.
`MicrometerObserver` hands every event to its `MeterCustomizer`; `DefaultMeterCustomizer`
records the job, LLM and limiter meters its javadoc lists. A customizer that throws is logged
at WARN and the observer goes on.

## The limiter stream from the inside

### Per-job attribution

`JobSnapshot snapshot` on each event is the snapshot the waiter captured when the job asked for admission, carried on the waiter while it is parked and on the `Grant` after it is granted. No ThreadLocal is involved: the evaluator thread emits `HELD`, `GRANTED_*` and refusal events for the jobs it evaluates, and the timeout executor emits `RELEASED` for a job it force-closes, both with the job's own snapshot. `LimiterEvents` is the one place a `LimiterEvent` is built and published; emission is wrapped so an observability failure never breaks admission.

### Identity: `LimiterIdentity`

Every row in the health snapshot, every metric tag and every event is named after a `LimiterIdentity`: `limiterName()`, `limiterCategory()` (`memory`, `token_bucket`, `elastic_window`, `semaphore`), `capacity()`, `currentInUse()`, `statusIndicator()` and `getStatus()`. An account resolves each amount it is asked about to one identity through `RateLimiter.accountFor`: a plain limiter is its own identity, a router answers with the bucket the amount debits. `Demand` normalizes entries by that key, so waiter counts, head reservation and events all agree on what "the account" is.

The accessors sit on the hot path of every emit and must be cheap: no sweep of stale state, no refill, no native heap reading per call. `statusIndicator()` returns a short tag (`"blocked"`, `"probing"`, `"throttle:2.0x"`, `"pace:1500ms"`) when the account is in an abnormal operational state, or `null` when nominal, and is carried on every event so consumers see the abnormal state alongside the numeric columns.

### Base class: `AbstractRateLimiter<T>`

The base for accounts that are their own identity. Eight ship with the runtime: `MemoryPressureGate` (heap), `DatabaseGate` (one permit per held connection), `HttpConnectionGate` (one permit per admitted HTTP-using job), `ModelGate` (one permit per request in flight to a model entry that declares `max_concurrent`), `TokenBucketRateLimiter` (per-model TPM/RPM), `ElasticWindowRateLimiter` (per-service window + circuit breaker), and `STDIOEndpointRateLimiter` with the pool-wide `mcp:stdio` gate (MCP subprocess slots); a host adds its own the same way. It provides:
- The wake slot: `onCapacityChange` stores the evaluator's wake and `capacityChanged()` runs it. Subclasses call `capacityChanged()` after any self-change that can only increase capacity: a throttle relaxed, a cap raised from headers, a circuit closing, a `give`. The wake is a bare unpark and may be run under any lock.
- `accountFor` returning `this`.
- A `replenishment()` default of `RELEASE`, which is right for the capacity gates. The two window-metered families (`TokenBucketRateLimiter`, `ElasticWindowRateLimiter`) override it to `TIME` so release leaves their debits alone.
- A `statusIndicator()` default of `null`.

`CountingGate` sits on top of it as the shape of every unit-permit gate: a capacity, an atomic in-use count, `earliestFit` always `null`, category `semaphore`, and a refusal when a demand asks for more permits than the gate has.

Neither class emits or counts waiters. `Admission` owns the wait, so it owns the events that describe it and the per-account waiter counts; an account only reports its identity and moves its counters.

### `EPORateLimiter` is a router

`EPORateLimiter implements RateLimiter<EPOService>` routes each amount to one of five private `ElasticWindowRateLimiter` buckets and answers `accountFor` with that bucket, so a demand with two EPO inputs on one service becomes one entry on that bucket, and events, waiter counts and head reservation key on the bucket under the names `epo:search`, `epo:retrieval`, `epo:inpadoc`, `epo:images`, `epo:other`. The router itself is not an identity and never appears as a row. Its `tryTake` compensates within the call: if a later bucket declines, the earlier buckets' takes are given back before it answers false. Its `onCapacityChange` fans the wake out to every bucket.

### `SystemHealthReporter`

One observer, subscribed broadly to `JobEvent.class` and filtered to `LimiterEvent` + `LifecycleEvent` in its predicate. The runtime registers none of its observers itself: the host subscribes it on the dispatcher beside `EventLogger`, where it wants the snapshot. The sole output is a periodic system snapshot, printed every `SAMPLE_EVERY` events, composed of five sections:

- **Limiters** sorted by utilisation descending, one row per tracked limiter. Each row carries a gradient bar (green through yellow to red), a name column, percent, waiter count, humanised used/total counts (`1.8M/2.0M`, `7.9G/34.4G`), per-category unit (`tok`, `req`, `slot`, `B`), and a rightmost **status column** populated only when the limiter's `statusIndicator()` is non-null (`blocked`, `probing`, `throttle:2.0x`, `pace:1500ms`). Rows with any abnormal status get tinted at the text level - red for `blocked`, yellow for throttle/pace/probing - while the gradient bar retains its own saturation colors so the tint and the bar both carry signal.
- **Memory** one line, every snapshot, from `MemoryPressureGate.getStatus().summary()` whether or not the gate has emitted an event: heap occupancy, the zone (`GREEN`, `DRAINING`, `CRITICAL`), how many jobs admission is holding on memory, and how long until the next release is due if no job finishes first. The gate also logs its own transitions as they happen: `Memory latch set` at warn and `Memory latch cleared` at info, each carrying the same summary.
- **Jobs running** prominent bold total, then a breakdown by `JobType` (THINKER, DOER, TOOL, GUARDRAIL, LLM_CALL, JOB, UTILITY). Each type's row (name, count, bar) is tinted in the type's color so the dominant type is obvious at a glance.
- **Workflows active** single-line count of workflows with at least one running job, derived at snapshot time from an active-jobs map keyed by jobId that is populated on `JobStartedEvent` and removed on `TerminalEvent`.
- **Queue depth** standard + fast queue sizes and the number of jobs parked in admission, from `JobDispatcher.getStatistics()`. The two dispatcher queues should be `0` under healthy operation - virtual threads drain them in a tight poll loop, and a non-zero reading means the dispatcher threads are stuck or crashed. Parked in admission is where backlog sits by design: jobs whose whole demand does not fit yet, holding nothing.

The snapshot is the single source of truth: there are no per-transition alert lines on HELD / REJECTED / GRANTED_FROM_HOLD. It fires often enough to catch transient pressure without the console flood per-transition alerting produces at peak throughput, and abnormal limiters surface through the status column and row tinting inside each snapshot.

### OTel / Micrometer integration

`DefaultSpanCustomizer` handles `LimiterEvent` by adding a span event named `limiter.<type>` with attributes `limiter.name`, `limiter.category`, `limiter.type`, `limiter.in_use`, `limiter.capacity`, `limiter.waiters`, plus `limiter.wait_ms` when the wait is positive and `limiter.reject_reason` when present. The span is the current job's span (from the `JobSnapshot`); limiter transitions appear as span events, not as separate spans, and only those that arrive while the job's span is open (see "The exporters" above).

`DefaultMeterCustomizer` records:
- `nucleo.limiter.acquire` counter on `GRANTED_IMMEDIATE` / `GRANTED_FROM_HOLD`, tagged `limiter` + `category` + `outcome` (`immediate` | `held`).
- `nucleo.limiter.wait` timer on `GRANTED_FROM_HOLD` and on `REJECTED` that had a prior HELD, recording `waitNanos`.
- `nucleo.limiter.reject` counter on `REJECTED`, tagged `limiter` + `category` + `reason`.
- No `jobId` tag on metrics - cardinality would explode. `jobId` lives only on OTel span events, where high-cardinality attributes are the norm.
- No gauges. A gauge needs a state mirror per limiter; counters and histograms answer the diagnostic queries the stream exists for, and a deployment that wants gauges writes a stateful customizer.

### Concurrency invariants in `ElasticWindowRateLimiter`

The account has no loop, no sleep and no waiter of its own; `Admission` does the waiting. What it must keep straight is the window against the circuit, because client threads move the circuit through `onSuccess` and `onRateLimitError` while the evaluator asks and takes. All lifecycle methods are `final`, and the invariants are:

1. The request window is one `ArrayDeque` of `System.nanoTime()` stamps under one lock; the circuit is one `AtomicReference` to an immutable `CircuitStatus` record, so every reader sees state and timing together.
2. `tryTake` reads the circuit, checks the window and appends the stamps inside the lock, so a circuit that trips on a client thread between the evaluator's `fits` and its `tryTake` makes the take refuse rather than land. The pre-check exists to keep a shortfall from touching any other account; the take is the truth.
3. `fits` and `tryTake` throw the LLM-readable refusal while the circuit is `BLOCKED` inside its cooldown or `PROBING` with a probe in flight. The refusal fails only the job being evaluated and names the upstream's last recorded failure.
4. The probe belongs to the head. Once the cooldown has expired, only a caller with nothing reserved ahead of it may claim the single `PROBING` slot; a waiter behind the head is refused as if the probe were already in flight, so a younger job can never take the one slot from the oldest waiter.
5. A probe whose grant is rolled back or compensated, or whose job dies without a verdict, does not pin the circuit: `give` reverts a pending probe to `BLOCKED` with its cooldown preserved, and a probe older than `getProbeTimeoutMs()` expires the same way on the next look.
6. `give` drops the newest stamps, the ones a failed or rolled-back grant just added, so every other waiter's deadline, which `earliestFit` computes from the k-th oldest stamp plus the effective window, is unchanged.
7. `onSuccess` and `onRateLimitError` call `capacityChanged()` only on transitions that can increase capacity (a throttle relaxed, a circuit closing). A throttle raised or a circuit opening wakes no one.
