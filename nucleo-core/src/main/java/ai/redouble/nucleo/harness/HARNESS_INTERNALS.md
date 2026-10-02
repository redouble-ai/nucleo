# Inside the job runtime

This page is for people working on the runtime itself: how the dispatcher records call
order, assembles and admits a demand, paces on memory, enforces guardrails and scope, and
the dispatch contract rule by rule with the tests that hold it. How to use the runtime is in
[The job runtime](PACKAGE.md).

## The Job Tree Records Call Order

A workflow is a tree: every job's snapshot carries its parent's job id, and a child a doer
submits through `submitInStep` / `submitInCurrentStep` carries its position in its parent's
execution order under the `AbstractOrchestrator.META_ITERATION` metadata key, which a
deployment's recorder persists. A parent that
runs A, then B, then C stamps A=1, B=2, C=3. Two children of one parent share an ordinal ONLY
when they genuinely run in parallel (a fan-out). That ordinal is what lets a trajectory be
reconstructed after the fact.

Because the ordinal is per-parent, a trajectory is read by walking the tree depth-first and ordering
each parent's children by `(iteration_number, started_at)` - never by flat-sorting all of a workflow's
jobs on a single column. A per-parent ordinal collides across parents, and a timestamp interleaves
parallel branches, so either flat sort looks like noise even when every ordinal is correct. Coded
doers stamp these ordinals through `submitInStep` / `submitInCurrentStep`; see
[Tools, thinkers and doers](../tools/PACKAGE.md) for the doer-author contract.

## From requirements to a demand

The system reads these declarations and makes global decisions. The dispatcher captures `getRequirements()` exactly once at submit and once per execution attempt (each capture mints fresh `ModelBinding`s - nothing re-invokes it), resolves every binding through the deployment picker and the compliance gate BEFORE any acquisition, prices the reservation against the resolved spec, then hands the job's whole demand to admission: the token reservation on each resolved model, the HTTP connection permit, each custom limiter, and one permit on each declared database provider's gate. Admission grants all of it at once or parks the job holding nothing (see "Admission: The Whole Demand, or Nothing"). Memory pressure is checked before anything. Because every waiting job's demand is on the queue, the system knows the total resource demand across all waiting jobs and can make admission decisions.

There is no partway. A grant is one critical section that takes every account or none of them. If materializing a database handle under the grant fails, the handles taken so far are closed and the grant is rolled back whole. No partial allocations, no leaked permits, no orphaned connections.

## Admission: The Whole Demand, or Nothing

The rule of no waiting while holding forbids a job from waiting on another job while holding resources. The same rule applies to the framework itself while it is handing resources out. A job that needs tokens on a model, a connection from the HTTP pool and a database connection must never take one of them and then wait for the next: the moment it does, it holds capacity that other jobs need while blocked on capacity that other jobs hold. That is hold-and-wait, one of the four conditions of deadlock, and in the degenerate case it needs no cycle at all. A saturated token bucket with a long line behind it is enough for every waiter in the line to hold an HTTP permit, the pool to empty, and jobs that never needed a token to wait hours for a connection that no one is using.

So there is no order in which resources are taken. A job's needs are one `Demand`: the ordered list of accounts it must hold at once with its amounts on each, assembled mechanically from its priced requirements. `Admission`, one per dispatcher, is the one place a job waits:

- **One evaluator.** A single virtual thread walks the waiting queue; a job that arrives at an empty queue is tried once inline, under the same lock. Every question to an account on a job's behalf is asked under that lock, so a grant is one critical section: every account says the amounts fit, every account is debited, or nothing is. An account that accepts the pre-check but declines the take (a cap lowered between the two, a circuit tripped by a client thread) has what was already taken given back inside the same section.
- **Nothing is held while waiting.** A job whose demand does not fit parks holding nothing, on a bare `LockSupport.park`. It has no permit, no token, no connection. When it is granted it has all of them.
- **The head is never overtaken.** Waiters queue in arrival order. Every account is asked about the head's amounts as well as the asking job's: a job behind the head can only take what is surplus above what the head is waiting to receive. A large demand at the head of a busy queue is therefore guaranteed to be served (it never starves), while jobs whose demands do not touch the head's short accounts pass it freely (the queue never clogs). This is the reservation used by backfilling batch schedulers, applied per account.
- **Memory is first, always, and never reserved.** The memory gate is consulted before any account at every grant attempt. While the heap is critical, nothing is granted to anyone; while jobs are queued, they are released one per interval. Memory cannot be booked in advance.
- **Nothing polls.** Every state change unparks the evaluator: a grant released, a rollback, an account reporting that its capacity grew, a new arrival. When a demand is short only on metered accounts, each account names the exact instant its shortfall closes from the clock alone, and the evaluator parks until the earliest of them. A token bucket has no refill thread; it is a level and a rate, read at the instant it is asked.
- **Faults are local, defects are loud.** An account that throws while answering about a job refuses only that job. It receives the account's message (an `UncorrectableRuntimeLLMException` when the account is refusing what can never fit, or refusing everyone while its circuit is open), and the pass continues for everyone else. Anything else that throws inside a pass is a defect in an account's `give` or in admission itself, and it is not survived: every parked job fails with it as the cause, every later admission refuses with it, and it is logged once with its stack trace. A broken monitor fails everyone; it never hangs anyone.

The grant is settled exactly once. `release` returns the accounts a finished job held for its duration; `rollback` returns everything, metered debits included, because the work never happened. A close that overlaps a force-close cannot return a permit twice.

Every one of those properties is a known result rather than a local invention: collective request over sequential acquisition, the monitor with a conjunctive guard over a chain of semaphores, conformance decided at the event instant, head reservation from backfilling schedulers. [ADMISSION.md](admission/ADMISSION.md) maps each decision to the result that dictates it, and lists what the alternatives are closed by. Read it before changing the shape of admission.

## Memory Is a Resource Too

Most systems ignore memory until they crash. Nucleo treats heap space as a managed resource, and it is the one resource nothing declares: no job knows how much heap it will hold, and no caller can be asked. So the gate does not budget; it holds a line and lets it go at a pace the heap can take. It throttles only what can endanger the process: a job that declared resources. An orchestrator holds nothing the gate manages and can only fan out work it already has, every fetch being a tool that meets the gate itself, so it never meets the gate at all, not the latch, not the drain.

**The latch.** At or above 95% heap no resourceful job is granted, and the latch clears only when a read sees the heap back below 90%. Memory is never reserved and never bookable in advance. Every job the latch refuses is held on the memory account; those jobs are memory's line, and a job that arrives while the line drains joins it. A line held on some other account, a database gate with all its slots taken, is that account's to release at its own pace whatever the heap reads.

**The drain.** While memory's line is non-empty, two things can happen, and whichever comes first wakes the evaluator: a job finishes, or the delay for the heap as it reads now elapses since the last release. The evaluator re-reads the heap; if the latch is not set it releases one, the first in line that can go, and the next check is the delay for the reading at that moment. The delay is a cubic curve, 50ms at the floor below 80%, 30 seconds at the critical boundary:

```
Heap usage:  0%              80%          85%        90%        95%
             |----------------|------------|----------|----------|
Delay:       50ms            50ms         ~1.1s      ~8.9s      latched
                                                     recovery at 90%
```

The curve alone is a function of the level, and the same level reached fast is a different situation from the same level reached slowly. So the wait is the curve's value multiplied by a growth penalty that every release moves by the live set's net growth over the last two releases, in percentage points: growth multiplies it by 2 to the points gained, a fall divides it by 2 to the points lost, no net growth keeps it. A live set that went 80, 83, 86 across releases waits sixty-four times the curve. If it then goes 89, 92, 89, 92, the sixty-four is what holds it there and it stays. If it climbs on to 89 the sixty-four compounds to four thousand. A jump from 80 to 92 in a single release is four thousand at once. The wait is capped at one hour and the penalty at the cap over the curve's 50ms minimum, so a fall of seventeen points from any state is a reset. Every look computes the penalty a release now would commit, so a heap that empties clears it at the next look, and the evaluator looks at least every two seconds under any wait.

The live set is read as the floor. Used heap is the live set plus the garbage allocated since the last collection, and between collections the garbage part swings by tens of points in seconds, so the reading itself says nothing about growth. Used heap only falls when a collection ran, so a reading lower than the previous look's is one taken after a collection, the live set plus what was allocated since; that reading is the floor, it holds until the next fall, and the penalty measures its growth. The curve and the latch read the level, the reading as it is. The status line carries both.

A completion is a moment to look, not a token to spend: the heap reading decides at every look, a completion that lands under the latch earns nothing, and ten completions under it are one look when it clears. There are no memory seats to count. Looking at completions is what keeps the collector running: a drain paced by the delay alone can go minutes between releases on a long line at a high reading, nothing allocates in that time, the collector never runs, and the reading it is waiting on never moves. With nobody in the line a job starts at once, at any heap below the latch.

**Waking.** Garbage collection signals nothing, so while anything is pending the evaluator is told to look again within two seconds. That is the one deliberate timed re-check in admission.

## One Interface for Every Gate

Token buckets for LLM tokens, sliding windows for external APIs, counting gates for concurrency caps, the heap gate for memory: they all share one contract, and none of them blocks. `RateLimiter<T>` is an account. `fits(mine, reservedAhead)` answers whether the asking job's amounts would fit right now with the head of the queue's amounts set aside, and throws an `UncorrectableRuntimeLLMException` to refuse what can never fit or to refuse everyone. `tryTake` performs the same check and the debit atomically under the account's own lock. `give` credits amounts back. `earliestFit` names the `System.nanoTime()` instant a shortfall closes from the clock alone, or `null` when only an event can change the answer. `onCapacityChange` installs the evaluator's wake, which the account runs after any self-change that can only increase capacity. `accountFor` resolves an amount to the `LimiterIdentity` it debits. `replenishment` says how a permit comes back; `onSuccess` and `onRateLimitError` carry the upstream's verdict. Each implementation picks the amount type that makes sense (`Integer` for token counts, `Void` for unit permits, a routing key for multi-bucket limiters) and returns a typed status snapshot implementing `RateLimiterStatus`.

`Admission` doesn't care what kind of account a demand names. `JobResources` assembles the demand mechanically from the priced requirements - one account per resolved binding (a token reservation on the entry's bucket when the entry is bounded by a quota window, one permit on the entry's `ModelGate` when it declares `max_concurrent`, the bound of a model served from a machine the deployment owns), the HTTP connection gate, each custom limiter declared via `JobRequirements.requireRateLimiter(...)`, one permit on each declared provider's `DBResourceProvider.admission()` gate - and `Demand` normalizes entries that debit the same account, so two bindings on one model become one entry with two amounts and the account sees the whole ask at once. Database admission rides this model: a deployment's `DBResourceProvider` exposes its `DatabaseGate` through `admission()`, the grant holds the permit, and `acquire` only materializes the connection under it, so the pool surfaces as a `db:<pool name>` row in the system health snapshot, named from the provider's `DBResourceProvider.name()`. The default provider, `CountingDBResourceProvider`, is that count alone: the job declares it, waits for a permit, and takes its connection from its own stack whenever it needs it. Stack-specific providers - plain JDBC, Hibernate, a Spring application's transaction manager - also hold the connection for the job, and any other stack implements the same contract. The HTTP pool is an account too: `HttpConnectionGate` (`http`), one permit per admitted HTTP-using job, sized to the pool.

`CountingGate` is the shape of every unit-permit gate: a capacity, an in-use count, and an `earliestFit` that is always `null` because a permit comes back only when a job gives it back. Database gates, model gates, MCP subprocess slots (per endpoint and pool-wide) and the HTTP gate extend it; a new concurrency cap is a subclass with a name.

Third parties extending Nucleo register their own `RateLimiter` implementations without touching the framework. Their accounts participate in grants, head reservation and rollback symmetrically with built-ins, and declare their own replenishment kind.

### Two Ways a Permit Comes Back

`replenishment()` splits every limiter into one of two kinds. The interface declares no default; `AbstractRateLimiter` answers RELEASE for the accounts built on it, and the two clocked families, the token bucket and the elastic window, override it with TIME.

A `RELEASE` limiter hands out something held for the duration of the work: a database connection, a concurrency slot, retained heap. Its capacity is how many things may be in flight at once, and `Admission.release` gives the permit back when the job's resources close.

A `TIME` limiter spends its permit against a window instead: tokens per minute, requests per second. Nothing the job does at close can return it, because only the passage of time can. Returning one anyway makes throughput a function of job duration rather than of the window, which silently defeats throttling - the limiter can raise its throttle coefficient to the maximum and the actual rate never changes, so it goes on to trip its circuit breaker and fail every waiter parked behind it.

So release returns only `RELEASE` accounts. Rollback returns every account, because a job that never ran never spent its window; so does the compensation inside a failed grant attempt, where an account declined the take after the pre-check said the amounts fit.

## Guardrails Are Enforced by the Dispatcher

Guardrail execution is not a courtesy of orchestration code - it is a property of the
dispatch path itself. A job that declares guardrails (every Tool is a `GuardedExecution`)
has its input-side guards run by the dispatcher after dependency resolution and before
resource allocation, and its output-side guards after resources are released and before
the result is delivered. The same pre-emptive geometry as rate limiting: check before
committing resources, at the one chokepoint every execution route crosses, so protection
is a property of the tool and the flow rather than of the route a call took.

A fourth kind, the validation guardrail, runs at a different seat: a goal-directed
thinker's final-answer branch, while the thinker still holds its conversation. A
refusal there is fed back to the thinker that produced the answer, as a correction
turn in its own context, and the candidate is discarded; the caller never sees a
refused answer and never sees the refusal either, only the answer that finally
passed - or null when the iteration budget runs out.

Scope is enforced one step earlier, at the submission door in `dispatch()`: only
orchestrators (`ScopeAuthority`) may submit from inside a job; an orchestrator's
effective `ScopeGuard` is composed from its caller's and sealed at its own dispatch;
every submission out of it has the child's scope and the input's claim judged against
the sealed guard, and descendants inherit by merge. A refusal is a born-failed handle
whose `get()` throws the `GuardrailException` wrapped in `ExecutionException` - model
output cannot alter a sealed binding, structurally. Guard jobs cross the same wall at
the internal door, judged against the guard the gated job was admitted under (an
orchestrator's own sealed guard; a leaf's live parent's): a scoped guard whose claim
drifts is refused before it runs, and a guard that declares `ScopeAuthority` is sealed
so a judge it spawns inherits the gated flow. A guard refused at that door rendered no
verdict, so the gated job fails with a `SystemException` carrying the refusal - a code
error in the guard, never a policy decision. See
[guardrails/PACKAGE.md](../guardrails/PACKAGE.md).

## The Dispatch Contract

The guide says why; this section says what, with the numbers, for `JobDispatcher` and the
types around it. The tests of this package hold each rule: `DispatchContractTest`,
`ResourceContractTest`, `KernelSubmissionContractTest`, `KernelExecutionContractTest`,
`JobResourcesContractTest`, `JobContextContractTest`, `JobIdentityTest`,
`JobStateTransitionTest`, `JobTerminationExceptionTest`, `MessageBusContractTest`,
`HeartbeatDispatchTest`, `HeartbeatStoreTest`, `ComplianceEnvelopeSealTest` and
`ObservableClientsTest`.

**Identity.** A job is built from a parent `Identifiable`; a root's parent is
`Job.workflow(userId, prefix)`, which refuses a null or empty user or prefix and mints an id
`prefix-<random tail>` that is its own workflow id with no parent. `AbstractJob` refuses a null
parent, mints `idPrefix-<tail>` (the class simple name when no prefix is given), inherits parent,
workflow and user from the parent, and has priority 0 and a name of `<class>-<id>`. The defaults a
job inherits: `getJobType()` is `JOB`, `getDisplayName()` is null, `getTimeout()` is 30 minutes
(`AbstractJob.setTimeout(null)` declares none). A `JobSnapshot` names a job by
`Job.getDisplayName()` when it answers, else `@DisplayName`, else the class simple name; the
action is the annotation's or empty; the description is a `@ToolDescription` read reflectively,
null without one. `JobState`: COMPLETED, FAILED, CANCELLED and TIMED_OUT are terminal; FAILED
and TIMED_OUT are failures; COMPLETED is the success; every other state, IDLE included, is
active. `AbstractStoppable.stop()` raises the stopping flag first and then stops, once; a second
`stop()` returns without stopping again. The runtime's two termination signals,
`JobCancelledException` and `JobTimeoutException`, are uncorrectable and explain themselves to a
model.

**The door.** Three walls, in order, each refusal delivered as a handle born failed:
`JobFailedEvent` is published, `WorkflowCompleteEvent(false)` for a root, `JobScheduled` never
fires. Only an orchestrator (`ScopeAuthority`) submits from inside a job, and a live job may be
named as parent only from its own thread, and never from outside any job: these two refuse with
a `SystemException`. A scoped child and a scoped input are judged by the caller's sealed guard,
and a claim outside it refuses with the guard's own `GuardrailException`; a `scope()` that
throws or answers null is a `SystemException`. A child orchestrator's effective guard is
composed and sealed. A job that declares resources and no timeout is refused at the
door, since admission's progress argument rests on every permit returning in finite time. A
dependency list that would close a cycle is refused with an `IllegalArgumentException` naming
the cycle. `getRequirements()` is invoked exactly once at submit and once per execution attempt;
nothing else calls it, since every call mints fresh model bindings. Initial metadata handed to
`submit` is on the context before `JobScheduled` fires, and a submission from inside a job
stamps the caller's class name under `obs.caller_class` on the child's context. A doer's
`submitInCurrentStep` adds its call ordinal the same way: sequential steps count up, and every
job of one fan-out shares one ordinal (`AbstractDoer` states the rule).

**Routing.** A job whose requirements decline queueing runs at once on a virtual thread. A job
with a timeout of five seconds or less enters the priority queue, served by the higher
`Job.getPriority()` first and by submission order among equals; every other job enters the
FIFO queue. Both queue threads poll every 100 milliseconds. `submitWithDelay` sleeps the delay
on a fresh virtual thread and submits as a root, completing its future with the handle or the
refusal.

**One attempt, in order.** Dependencies are awaited first, holding nothing: a failed dependency
fails a non-tolerant job with `DependencyFailedException` before it runs, and a tolerant one
runs with a null result and the failure under `getDependencyErrors()`; results are keyed by the
dependency's final snapshot, in declaration order, and `singleDependencyResult()` refuses zero
or several with `IllegalStateException`. Then the input guards. Then the attempt's requirements
capture, model resolution, pricing and the spend gates. Then admission of the whole demand: an
interrupt while parked is an `UncorrectableRuntimeLLMException` with the interrupt restored.
Then the timeout is armed, `preExecute` runs once with resources held, the state becomes
RUNNING and `JobStartedEvent` fires; a re-run repeats all of it, so an execution of three
attempts publishes three `JobStartedEvent`s. Then `execute`, inside `beginAll`/`commitAll` when
the job declares a provider and a transaction, with `rollbackAll` on a failure and a failed
rollback attached to the failure as suppressed. A success signals `onSuccess` to the job's
custom limiters before the resources close; an `ExternalServiceException` signals
`onRateLimitError` with the `UpstreamFailure` read off its cause chain. Resources close, output
guards run against the result (a refusal turns the success into a failure with the refusal as
cause), `postExecute` runs, and one terminal event fires: `JobCompletedEvent`, `JobFailedEvent`,
`DependencyFailureEvent`, `JobCancelled` or `JobTimedOut`, followed by `WorkflowCompleteEvent`
for a root. Then the job is torn down: it leaves the running set, the conversations it owns are
released, and its recorded LLM calls are released (`JobContext.clearCompletionData`). The handle
settles last on every path, after the workflow event and after the teardown: the result, or an
`ExecutionException` whose cause is the failure. So a caller woken by `handle.get()` always finds
the job finished, never still being torn down, and a finished job's calls are the ones its
terminal event carries (`TerminalEvent.getLlmResponses`); its context holds none. An observer registered through
`JobHandle.registerWorkflowObserver` on a root's handle is unsubscribed at the settle, so it
sees its workflow's terminal event on both outcomes.

**Re-runs.** An `UpstreamRetryException` re-runs the attempt transparently: the resources close
first, the thread waits a random delay in the signal's own jitter window multiplied by the
attempt number (at most tenfold, and never past 300 seconds), and the next attempt re-captures
requirements, re-resolves models with the failed attempts as history, and re-admits. The
re-runs are counted on one shared counter per execution against the job's own budget,
`Job.getUpstreamRetries()` (`Job.DEFAULT_UPSTREAM_RETRIES`, three, unless the job or its caller
set another: every re-run is minutes of scaled pacing a caller waits behind, and a fault still
there after a few is an outage or an unservable request; a thinker hands its budget to every
model call it makes, an `LLMCall` and a decision thinker's `DecisionCall` alike), then
`UncorrectableRuntimeLLMException` naming the budget, with the last
signal as cause. An
`OutputTruncationRetryException` re-runs once with the escalated budget; a second truncation is
the same refusal, saying whether the job rebuilt its conversation or failed over to a smaller
model. A `ResponseCorrectionRetryException` re-runs up to four times as a backstop behind the
job's own correction budget, then the same refusal. A cancellation is never re-run and never
charged to a limiter. An `Error` thrown by a job is a `SystemException` of component `job`,
never re-run.

**Timeouts.** The grace period between the two phases is ten percent of the timeout, never
under 500 milliseconds and never over 30 seconds. Phase one sets the timeout and cancel flags,
runs the cancel callbacks, and closes the resources on a virtual thread of its own; phase two
interrupts the job and force-closes. The state becomes TIMED_OUT, `JobTimedOut` fires, and the
job's own casualty reaches the caller as a `JobTimeoutException` unless it already is
LLM-readable.

**Waiting.** `JobHandle.get()` and `get(timeout, unit)` both refuse a caller that holds
resources with a `JobDeadlockException` naming both jobs and the call site; an orchestrator
holds nothing, so it waits on its children freely. `JobHandle.cancel(boolean)` is the
cooperative cancel of `JobDispatcher.cancel(jobId, reason)` under the `Future` name: the flag
carries no meaning, and the answer is whether the job was found queued or running.
`registerWorkflowObserver` subscribes an observer to the handle's workflow only and unsubscribes
it when the handle settles. `AbstractWorkflowObserver` filters by workflow, unsubscribes itself
on a `WorkflowTerminationEvent`, and reports stale after its inactivity timeout.

**The context.** `publish(data, percent)` moves a QUEUED or SCHEDULED job to RUNNING and
records the progress; `publishUserProgress(title, message, percent)` publishes
`title: message`; `publishUserNotification` publishes a `UserNotificationEvent` of the given
severity. `onCancel` and `onTimeout` callbacks run once, and at once when registered after the
fact; registration and firing share one monitor, so a callback registered while the firing is
under way is either in it or run by its registrar, never both. A completed job never times
out, so a timeout callback registered once the job has completed is dropped, not queued. The
context's
`CancellationToken` keeps the same rule for its own `onCancellation` callbacks, and a token of
its own never touches a context. `getRemainingTime()` is null for a job with no deadline.
`recordResourceWait` sums per account under `wait_times`; `recordAdmissionWait` sums the wall
wait under `admission_wait_ms`. A context refuses a null or blank user id. `setState` from a
terminal state throws `IllegalStateException`: once terminal, a job stays so. `currentJob()` is
null on a thread outside any job and the job's own context on its thread, orchestrators
included; `isExecutingJobWithResources()` is true only between acquisition and release. The
LLM and embeddings clients a job receives are `ObservableLLMClient` and
`ObservableEmbeddingsClient`: every call lands on the context's response list, a delegate's
response as it is and a failure as a response marked unsuccessful with the reason, the cause
and the model, a truncation with the `MAX_TOKENS` stop reason; `clearCompletionData` releases
the list when the job finishes, before its handle settles. `JobResources.get(provider)` and `getHandle` refuse
a provider the job did not declare with `IllegalStateException`; `commitAll` commits in
declaration order and, on a failure, rolls back the uncommitted remainder in reverse and throws
an `UncorrectableRuntimeLLMException` with every rollback failure suppressed; `rollbackAll`
skips what committed; `close` closes every handle and then releases the grant, aggregating
close failures into one `IOException`; `forceClose` aborts and closes each handle and releases
the grant, which settles once.

**The bus.** `LinkedQueueMessageBus` drops a message published before `start()` or after
`stop()`; what was published before `stop()` is delivered before it returns, each consumer
getting up to 5 seconds to drain its queue. A normally-paced observer's backlog is delivered
whole; an observer whose backlog cannot clear within that window is interrupted and the
remainder dropped, the bound existing so a wedged or slow observer cannot hang shutdown. An
unsubscribed observer likewise receives what was already enqueued for it and nothing more,
and the unsubscribe never blocks its caller. A second `start()` is a
no-op; `stop()` on a bus that is not running, or a second
time, throws `IllegalStateException`, and so does `subscribe` while stopping. Each subscription
has a queue of a million events, and an event past that is dropped with an error log. An
observer instance already subscribed gets a no-op subscription and a warning. An observer that
throws is logged and keeps receiving; a predicate that throws rejects that event. Stale
subscriptions are reaped every 60 seconds and on `cleanupStaleSubscriptions()`.

**Cancelling and shutdown.** `cancel(jobId, reason)` on a running job signals its context and
answers true; on a job still in a queue it removes the job and settles it there, since nothing
downstream ever will: the state becomes CANCELLED, `JobCancelled` fires, a root's
`WorkflowCompleteEvent(false)` follows as on every other terminal path, and the handle fails
with a `JobCancelledException`. The queue threads take a job the instant it is offered, so a
job is in a queue only while the thread that serves it is busy taking the one before; the
settlement itself is what the tests pin. `shutdown(timeoutMs)` refuses every parked waiter
first, then stops the queue threads, waits the timeout (30 seconds when zero is passed) for
running jobs and interrupts the rest, settles every job still queued the same way, stops each
registered `Stoppable`, and stops the bus. `start()` twice is a no-op with a warning, and every
submission before `start()` is refused with `IllegalStateException`.
`sealComplianceEnvelope` is accepted once and refuses null; a second seal throws
`IllegalStateException`, and a read before any seal answers the refusing default without
sealing.

A running job that is cancelled finds it at its next `checkCancellation()`, which throws
`JobContext.CancellationException`, or at `isCancelled()`. That exception is the runtime's
cancellation signal, an uncorrectable member of the sealed hierarchy: it leaves a tool's
`execute` as itself, `LLMReadableCheckedException.unwrap` returns it as itself, and the
dispatcher recognizes it as the job unwinds, records CANCELLED, publishes `JobCancelled`, and
settles the handle with a `JobCancelledException` wrapping it. If a job that never consults
its token completes, its state is already CANCELLED and stays so, and the caller gets the
cancellation. State transitions are atomic, so a cancel racing a completion has one winner and
no exception on either thread.

## Heartbeats: authority and continuity

The heartbeat's `Heart` is two virtual threads constructed by `JobDispatcher.start()`, one
consuming the bus into the `HeartbeatStore`, one parking until the next due instant.

The security shape is capture-and-replay through the door. The publisher-facing record
carries INTENT ONLY - nothing to forge. The stored `Heartbeat` (final class,
package-private constructor) is minted at exactly two places: `JobContext.publish`,
which stamps the publishing orchestrator's identity and sealed ScopeGuard (and refuses
non-orchestrators with the door's own rule - scheduling IS deferred submission), and
`JobDispatcher.scheduleHeartbeat(spec, userId)`, the authority-bearing boot/operator
entry. At fire time the captured guard plays the caller-guard role in the SAME
`admitSubmission` every live submission passes - judgment and merge both - so a
self-scheduling flow cannot strip its inherited scope, and future door hardening
applies to fires automatically.

Conversation continuity is a typed opt-in: a heartbeat naming a `conversationId` fires
only a job class implementing `ConversationCarrier` (the thinker substrate does; its
adoption machinery hydrates INSIDE the fired job's execution, so the Heart's dispatch
thread never does I/O and slow hydration shows inside the fired run's wall clock). A
non-carrier job class with a conversationId fails the fire loudly - implementing the
interface IS the claim of adoption behavior, so a coincidentally-named setter can
never silently swallow the continuity obligation. The optional `input` payload rides a
typed `setInput` setter instead, failing loudly on type mismatch.

Everything is observable: six typed events under the sealed `HeartbeatEvent` category
(`HeartbeatRequested`, `CancelHeartbeat` - open-publish, denial-shaped -
`HeartbeatScheduled`, `HeartbeatFired` carrying the store-id/job-id correlation,
`HeartbeatFireFailed` - after which a recurrence still enqueues its next instance -
and `HeartbeatRebound` for most-recent-wins id reuse), plus `store.inspect()` and the
`Stethoscope` observer base for dashboards and audit sinks. The platform ships the
in-memory store only: fixed-cadence schedules are re-published by boot code on every
start, and a deployment that needs adaptive schedules to survive restarts plugs in a
durable store. After a pause, all overdue fixed-anchor slots fire back-to-back - the
catch-up burst is deliberate and self-explanatory in the event timestamps.

The records validate themselves: `ScheduleHeartbeat` refuses a null job class, a blank id
or a null run instant, `Recurrence.FixedInterval` refuses a zero period, and a `Heartbeat`
refuses a blank user. `Heartbeat.next(instant)` is the recurrence's copy: the same job
class, id, conversation id, input, recurrence, user and sealed guard under the new instant.
A Heart that is stopping drops every request and cancellation it is handed, with a warning.
`HeartbeatDispatchTest` and `HeartbeatStoreTest` hold these rules.
