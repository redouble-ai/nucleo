# Inside admission

This page is for people working on the runtime itself. It is the package's statement of
record: every rule a caller or an implementor of an account can rely on is stated here, and
the tests named beside each section hold it. How a job uses admission is in
[Admission](PACKAGE.md), and why admission has this shape is in
[Why admission has this shape](ADMISSION.md).

## What is here

| Type | Role |
|---|---|
| `Admission` | The monitor, one per dispatcher. `start` once, `admit`, `release`, `rollback`, `stop`, `queueSize`. |
| `Demand` | A job's whole need: entries in the order their first amount was added, normalized by account key. |
| `Grant` | A demand after admission took it, settled once, with the wait attribution. |
| `RateLimiter<T>` | The account contract every gate and limiter implements. |
| `LimiterIdentity` | What an account is called in every event, health row and wait attribution. |
| `AbstractRateLimiter<T>` | The base for accounts that are their own identity. |
| `CountingGate` | Every unit-permit gate held for the work; `DatabaseGate` is one, named `db:<datasource>`. |
| `ModelGate` | The account of a model entry bounded by how many requests its server takes at once (`max_concurrent`) rather than by a quota window: one permit per request in flight, named after the entry, `RELEASE` replenishment. Built by the factory for such an entry in place of a token bucket. |
| `MemoryPressureGate` | The heap account: latch, drain, growth penalty, floor. Its javadoc is the full statement of that rule. |
| `TokenBucketRateLimiter` | The per-model `TIME` account: requests and tokens per minute, clocked, with the adaptive throttle. |
| `ElasticWindowRateLimiter` | The per-service `TIME` account: a sliding window, an elastic throttle, a circuit breaker. |
| `RateLimiterFactory`, `RateLimiterRegistry` | Every limiter instance in the process, and the per-model facade over it. |
| `LimiterEvents` | The one place a `LimiterEvent` is built and published. |
| `RateLimiterStatus` and its records | `TokenBucketStatus`, `ElasticWindowStatus`, `SemaphoreLimiterStatus`, `MemoryPressureStatus`. |
| `RateLimitInfo`, `RateLimitType` | What a provider's response headers said about its limits, as the clients capture it. |
| `SpendGate` | The money wall, consulted after pricing and before admission; implemented by the cost ledger. |
| `DBResourceProvider`, `DBManagedResource` | The contract a database stack implements to be managed by Nucleo. |
| `DBResourceProviders` | The process's database providers by name, for jobs built by reflection. |
| `CountingDBResourceProvider` | The default database provider: a `DatabaseGate` counting declared use, holding nothing. |
| `DatabaseSettings` | The default provider's knobs: `name` and `maxConcurrent`, bound from `nucleo.database.*`. |

## The monitor

`AdmissionTest`, `AdmissionEventTest`, `MemoryGateDrainTest` hold this section.

- `start` runs the one evaluator; a second `start` throws `IllegalStateException`.
- `admit` grants the whole demand in one critical section or parks the calling thread holding
  nothing; a demand that fits with the queue empty is granted inline. `queueSize` is the parked
  count.
- Waiters form one FIFO queue. Every waiter behind the head asks each account with the head's
  amounts on that account set aside: the head is never overtaken, a demand disjoint from the
  head's short accounts passes at the first pass, and one give cascades grants in arrival
  order. A pass over ten thousand waiters completes in well under two seconds.
- A demand with entries meets the memory gate before any of them, at every attempt; a demand
  with no entries, an orchestrator's, never does.
- Every state change unparks the evaluator; a metered shortfall parks it until the earliest
  computed deadline; an `earliestFit` of null means only an event can change the answer.
- An account that throws about a waiter refuses that waiter only, with the account's message as
  an `UncorrectableRuntimeLLMException` (a bug in the account travels as its cause); anything
  else that throws inside a pass is a defect that closes admission and fails every parked waiter
  and every later `admit` with it as the cause.
- An interrupt withdraws the waiter with `InterruptedException`; a grant that landed
  concurrently is rolled back, so an interrupted job never keeps a permit. A job cancelled
  through its context leaves at once with `CancellationException`, and a cancelled head stops
  reserving.
- `stop` refuses every parked waiter with `CancellationException`, clears every wake slot, and
  every later `admit` refuses the same way.
- A grant is settled once: a second `release` or `rollback` is a no-op. `release` returns the
  `RELEASE` accounts; `rollback` returns everything, metered debits included. Random demand
  shapes over random gates and meters never overdraw an account and every waiter is granted.

### Events

`Admission` emits every `LimiterEvent`; no account emits for itself. The vocabulary: a fast
path emits `GRANTED_IMMEDIATE` and nothing else; a slow path emits `HELD` once per account the
waiter was short on, then `GRANTED_FROM_HOLD` with the wait and the post-transition waiter
count; `REJECTED` carries a reason, `cancelled` for an interrupt or a context cancellation,
`circuit_blocked` for an account's LLM-readable refusal, `aborted` for an account's bug, and
`shutdown` for `stop`; `release` and `rollback` emit `RELEASED`. Every event carries the
waiting job's snapshot. N slow-path waiters on one account produce exactly 2N transitions with
balanced counts.

## Demand and Grant

`GrantAndDemandTest` holds this section.

- `Demand.add` merges an amount into the existing entry when the same limiter already carries
  one that debits the same account; `getEntries` keeps the order their first amount was added
  and is read-only; `isEmpty` is true for an orchestrator's demand.
- `Grant.getWallWaitMs` is asking to granted. `getWaitTimesMs` is how long each account that
  refused the job kept it, keyed by `limiterName()`, read-only, empty for a job granted at
  once; the accounts overlap in time, so the values do not sum to the wall wait. `isEmpty` is
  true for an orchestrator's grant.

## The account contract

`AccountContractTest`, `ReplenishmentTest`, `DatabaseGateTest` hold this section.

An account answers about amounts and moves counters; it never parks anyone. `fits(mine,
reservedAhead)` is whether the asking job's amounts fit now with the head's amounts set
aside; it throws `UncorrectableRuntimeLLMException` when `mine` can never fit the configuration
or the account is refusing everyone, and answers false, never throws, when `mine` fits alone but
not together with the reservation. `tryTake` is the same check and the debit in one step under
the account's own lock. `give` credits back: at close for `RELEASE` accounts, at rollback and as
compensation inside a failed grant for every account. `earliestFit` is the `System.nanoTime()`
instant at which `fits` would become true from the clock alone, null when only an event can
change the answer, and a past instant means now.

`replenishment()` has no default on the interface. `AbstractRateLimiter` answers `RELEASE`,
which is what a permit means for the capacity gates built on it; the two clocked families
override with `TIME`, and `ElasticWindowRateLimiter`'s override is final. A `TIME` window still
binds after the work that took a slot has finished; `give` remains the rollback path.

`AbstractRateLimiter` is its own `accountFor`, reports a nominal (null) `statusIndicator`, and
holds the one wake slot: `onCapacityChange` installs it, `capacityChanged()` runs it, installing
null clears it. On the interface, `onSuccess(input)` and `onRateLimitError(failure, input)`
delegate to the plain calls, `requiresHttpConnection` is false, and `observeHeld` is a no-op that
never consults the supplier it is handed.

`limiterCategory` is one of `memory`, `token_bucket`, `elastic_window`, `semaphore`.
`statusIndicator` is null when nominal, else `blocked`, `probing`, `throttle:<n>x` or
`pace:<n>ms`.

A `CountingGate` needs a positive capacity, else `IllegalArgumentException`; refuses a demand
larger than its capacity on the check and on the take; answers null to `earliestFit`; stamps
`lastActivity` on every take and give; and reports `maxConcurrent`, `availablePermits`,
`activeCount` and the stamp in its `SemaphoreLimiterStatus`.

## The memory gate

`MemoryPressureGateTest` and `MemoryGateDrainTest` hold the gate's javadoc, which is its full
rule: the latch at 95 clearing only below 90, the two-second re-check while anything is
pending, the drain one at a time by a completion or the delay for the heap as it reads now,
the cubic curve from 50 ms at 80 to 30 s at 95, the growth penalty over the floor, the closed
delay reported as its instant. A lone arrival is never spaced, in any zone below the latch. A
line held on some other account is that account's to release, not memory's, whatever the heap
reads. Its status names a zone: `CRITICAL` while latched, `DRAINING`
while memory's line is non-empty, `GREEN` otherwise; its category is `memory`. It logs the
latch setting at warn and clearing at info with the same summary. Once stopped it answers true
to everyone. Under random heap readings and completions every job is granted and no permit
leaks.

## The token bucket

`TokenBucketAccountTest` and `TokenBucketRateLimiterTest` hold this section; the weighted
backpressure rows are held in `harness.errors.retry`'s `UpstreamRetrySignalTest`.

- Clocked, never ticked: the level is the stored level plus the refill since it was last read,
  capped at the budget; the deadline is the deficit over the rate; requests are the second
  dimension; more requests than the minute allows, or more tokens than the budget, is refused
  outright; a cap lowered under a parked amount refuses it at the next check; the debit is atomic
  against a clamp between the check and the take.
- A refund is immediate and bounded by both caps.
- `record429` adds 0.5 to the throttle; `recordBackpressure(n)` adds n increments, weighted by
  how hard the signal asks the fleet to yield; the throttle is capped at 9.0; `onRateLimitError`
  logs the upstream's summary at warn and records one increment. Every third success takes 0.2
  off; after five minutes without backpressure every success takes 0.5 off; successes on a
  healthy bucket are no-ops. A relaxation wakes the evaluator; a tightening never does.
- `setConfig` applies new caps in place, keeps the throttle, clamps the level down when the caps
  shrink, wakes the evaluator when they grow, and is a silent no-op when the pair is unchanged.
- The name is the model, the category `token_bucket`, the capacity the token budget, the
  indicator `throttle:<n>x` above zero, and `getStatus` reads the level from the clock at that
  instant.

## The elastic window

`ElasticWindowRateLimiterTest`, `ElasticWindowStatusIndicatorTest`, `ReplenishmentTest` hold
this section.

- A fresh window is `HEALTHY` at throttle 0; a take counts in use; the effective window is the
  base window times one plus the throttle. Each pressure signal adds 1.0, capped at 9.0; three
  successes take 0.2 off and never below zero; after five minutes without pressure every success
  takes 0.5 off; a relaxation wakes the evaluator, a tightening does not.
- `earliestFit` is the instant the k-th oldest stamp leaves the window, k being the deficit;
  `give` drops the newest stamps, so the oldest, which every deadline reads, stay.
- The circuit opens after three failures at max throttle and refuses everyone while the cooldown
  runs; after it, the head of the queue claims the one probe and a follower is refused as if the
  probe were in flight; a success closes the circuit; a failure re-opens it with the cooldown
  doubled up to the cap; a refund with no verdict reverts the probe; a probe without a verdict
  expires after the probe timeout. Advisory pressure raises the throttle and never opens the
  circuit. Concurrent failures open the circuit once and leave it blocked; concurrent status
  reads never drop a live stamp; mixed concurrent operations leave the state within its bounds.
- The constructor validates eight template values with `IllegalArgumentException`: max requests,
  base window, initial cooldown, failures at max to block, successes per decrease and probe
  timeout positive; max throttle non-negative; max cooldown at least the initial cooldown.
- Every window needs the HTTP gate. The default name drops a `RateLimiter` suffix from the
  simple class name. `getCooldownRemainingMs` is zero unless blocked. The window's stamps, its
  cooldown and its recovery interval read one clock, which the tests drive.

## The factory

`RateLimiterFactoryTest` holds this section.

- One instance per concrete limiter class, built through a no-arg constructor of any visibility;
  a class the factory cannot build is refused with an `UncorrectableRuntimeLLMException` naming
  the class and the reason, the reflective failure as its cause: a deployment fault, not a
  model's to correct.
- One bucket per `ModelSpec`, keyed by the spec's id, seeded from its `tpm` and `rpm`; a spec
  that publishes no rpm gets `tpm / 1000` as its request budget. Endpoint variants of one wire
  model keep separate buckets because their ids differ.
- One `ModelGate` per `ModelSpec` that declares `max_concurrent`, of that capacity, named after
  the entry (`gate(spec)`); a spec that declares none is refused a gate, since its account is
  the bucket (`DecisionDemandTest` in `harness`).
- `updateLimits` applies a provider's reported ceilings in place, keeping the adaptive state,
  creates the bucket when none exists yet, and ignores a pair in which either number is not
  positive.
- `RateLimiterRegistry` is the facade: the same bucket, `updateLimits`, `getStatus`, `clear`,
  all the factory's. `clear` empties everything, for tests.

## Events and statuses

`AccountContractTest` holds this section.

- `LimiterEvents.amountOf` sums numeric amounts and counts one per anything else. Emission is
  best effort: with no bus nothing is built, and a failure to build or publish the event is
  dropped; admission never hears of either.
- Every status record renders a one-line `summary()` naming its numbers. `TokenBucketStatus.hasCapacity(n)`
  is a request slot and at least n tokens available; `SemaphoreLimiterStatus.activeCount()` is
  the maximum less the available.

## Header information

`RateLimitInfoTest` holds this section.

`RateLimitInfo.rateLimited(model)` is the record for a limit that was hit and nothing more: no
limits known, nothing remaining, a sixty-second retry-after, kind `UNKNOWN`. `fromHeaders` is a
`CAPACITY` reading that was not rate limited, with no retry-after and no request limits. The
per-minute fields mirror the limits and read zero when a limit is absent; a null kind reads as
`UNKNOWN`; `parseIntOrNull` trims and answers null for a missing or non-numeric header.

## The database contract

A provider exposes its bound as an account through `admission()`; `Admission` takes one permit
on it as part of the job's whole demand, and only then does Nucleo call `acquire`, which
materializes the handle under that permit and never gates. The permit is admission's, held in
the grant and returned at release; the handle holds the session, the commit latches and the
transaction state, never the permit. The lifecycle methods are the provider's own contract.

`CountingDBResourceProvider` carries nothing on its handle, its transaction verbs do nothing,
and it depends on no database API (`CountingDBResourceProviderTest`, which measures on a real
pool that jobs sharing a bound never have more connections out than the bound).

`JdbcResourceProvider`, `HibernateResourceProvider` and `SpringTransactionResourceProvider`
each hold one connection per permit from `acquire` to `close` and never touch the host's pool.
`DBResourceProviderContract`, in this package's tests and shipped in the test-jar, is the
contract they all pass: a real in-process database behind a real Hikari pool, whose live
active count is what "one permit is one held connection" is measured by, down to a fan-out
through the dispatcher that never holds more connections than the bound.

`DBResourceProviders` registers each provider once; a second provider under a registered name
is refused, and a miss names what is registered (`DBResourceProvidersTest`).
`DBResourceProviders.registerDefault()` registers a `CountingDBResourceProvider` of
`maxConcurrent` permits under `name` (`default` unless set); with `maxConcurrent` unset it
registers nothing and answers null; the pool's size is the host's, and no bound is inferred
from it (`DBResourceProvidersTest`).
