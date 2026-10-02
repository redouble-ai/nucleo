# Admission: why it has this shape

[Admission](PACKAGE.md) described what a job experiences: it declares everything it needs,
receives all of it in one step or waits holding nothing, waits in a line whose head is
protected, and is woken by events and by the clock, never by polling. That looks unusual for
a resource manager. None of the limits can block on its own, all granting happens inside one
monitor, a waiting job holds nothing, the head of the line has a reservation, and a wait for
tokens is a computed deadline where most code would sleep and retry.

None of it is invented here. Each of those choices is a known result from the literature on
deadlock, synchronization, traffic shaping and scheduling, and this essay maps each decision
to the result that dictates it. It is written for someone deciding whether a change to
admission improves the design or reopens a problem that was already solved.

## The problem

A job's needs are of two kinds.

- **Held** resources are counters with a capacity: a database connection, a place in the
  HTTP pool, a slot on a local model server, a subprocess slot, room on the heap. A grant
  takes one away and the job's completion gives it back. This is a semaphore.
- **Metered** resources are levels that the clock refills at a fixed rate up to a cap: a
  model's tokens per minute and requests per minute, a web service's requests per second. A
  grant spends from the level, and completion gives nothing back, because the provider has
  already counted the spend. This is a token bucket.

In the code the two kinds are the `RELEASE` and `TIME` answers of
`RateLimiter.replenishment()`.

Admitting a job is one action: when every counter it names can cover its amount, take from
all of them. Taking them one at a time instead is what the deadlock literature calls
**hold-and-wait**: holding one resource while waiting for another. Coffman, Elphick and
Shoshani list it as the second of the four conditions that must all hold for a deadlock. Here
it cannot deadlock, because a metered level refills whatever anyone does, so no cycle of
waiting can close. It does something quieter and just as bad: the plentiful resource is held
hostage by the scarce one. Jobs waiting for tokens each hold a place in the HTTP pool, the
pool empties, and throughput collapses while every counter, looked at on its own, reads
healthy.

The two kinds also change for different reasons. A held counter changes when another job
gives back what it held; a metered level changes as time passes. One waiting job may need to
be woken by either.

## Decision by decision

### The whole demand or nothing

In 1968 Havender listed four ways to keep processes from deadlocking over resources:

1. request resources in one fixed order;
2. request them all together and proceed only when all are granted;
3. release everything held and request all of it again together;
4. never wait while holding: when a request is denied, do something else.

A fixed order (approach 1) removes deadlock and nothing else. The job still holds its first
resource while waiting for its second; the wait simply cannot form a cycle. That is why
reordering a sequence of acquisitions only moves the hostage from one resource to another.
Approach 3 turns into approach 2 by Havender's own wording, since the new request is made
all together. Approach 4 needs a caller that can be told no, and a job that is meant to wait
for its turn is not one. Approach 2 is what `Admission.admit` does. Its known weakness, that
an all-or-nothing request can wait forever while a popular resource keeps being taken by
others, is exactly what the line's order, below, answers.

### Two acquisitions in a row cannot be made into one

A semaphore offers two operations, Dijkstra's P (take one, waiting if none is free) and V
(give one back). Patil argued that P and V are too weak to take several resources at once
safely, and proposed a generalized P that takes from several semaphores only when all of
them can give. Parnas showed that the impossibility argument rested on artificial
restrictions, and then conceded the two points that matter here: forbidding busy waiting is a
realistic restriction, and taking several resources at once belongs in a monitor that sees
all of their state together, never in a chain of single P operations.

Reed and Kanodia built the deadlock-free simultaneous P and stated the property directly: it
suspends the caller until several resources can be locked at once, and unlike two P
operations in a row it cannot deadlock among processes whose requests overlap. Their
mechanism also gives admission its internal rule: a caller takes its place in the line under
a short lock and then waits outside every lock, so no thread ever waits while holding the
monitor.

This is why no limit in Nucleo has a blocking method. `fits`, `tryTake`, `give` and
`earliestFit` answer and return at once. A blocking `acquire` on any one limit would be a
wait inside the very exclusion that taking several at once needs.

### A monitor whose condition covers the whole demand

A monitor is an object whose methods run one at a time, with condition variables that a
method can wait on, giving up the monitor while it waits. Hoare's monitor added two things
that matter here: waiters resume in first-come order, because that rules out one caller
overtaking another forever, and a method can wait on an arbitrary Boolean condition over the
monitor's state. Hoare called that conditional wait the easiest synchronizing facility to
use, and expensive only because the condition may depend on each caller's own data and must
be re-evaluated every time any caller leaves the monitor. Hoare's earlier conditional
critical region and Brinch Hansen's `region r when B do S` are the same construct.

Kessels removed the cost by allowing conditions only over the monitor's permanent variables,
evaluated when a caller leaves the monitor, with the right waiter signalled automatically.
Andrews and Schneider record the limitation that follows: an operation's parameters may not
appear in its condition.

A job's demand is exactly such a parameter: what a job asks for differs per job. The
standard resolution is the one `Admission` uses: make the waiting line itself part of the
monitor's state. The condition becomes a question about permanent variables, "does the
demand at the head fit?", re-evaluated on every event that moves a counter. Admission's
evaluator thread is that re-evaluation, and waking the one waiter who can now go, instead of
every waiter at once, is what Cargill calls specific notification.

The same result is why the memory gate's throttle is a spacing between grants, expressed
through `earliestFit`, and not a delay each job sleeps on its own. A private waiting room
outside the monitor has one signal for everyone parked in it, so the first job to finish
after a line forms wakes the whole group and the graduated delay is never served. Inside the
monitor the delay is the interval between two releases, and the evaluator wakes exactly one
waiter when it elapses. The interval's length is the heap level's curve multiplied by a
penalty that each release moves by the live set's net growth over the last two releases: up
on growth, down on a fall, unchanged when it holds. So the same level reached fast is a
longer interval than the same level reached slowly, and a penalty that stopped a climb stays
for as long as the live set stays where it stopped. The live set is read as the floor, the
reading after the most recent fall the gate saw, because used heap only falls at a garbage
collection; the garbage that accumulates between collections raises the reading and never
the floor.

### Levels read from the clock, and a wait that is a deadline

Telecom networks shape traffic with the Generic Cell Rate Algorithm, which has two provably
equivalent forms: the leaky bucket, a level drained continuously, and virtual scheduling. The
virtual-scheduling form keeps one theoretical arrival time and decides whether each request
conforms at the instant it actually arrives. It does not simulate a bucket being refilled; it
replaces the refilling process with the clock, and it costs nothing while no requests come.

Two consequences are visible in the code. `TokenBucketRateLimiter` has no refill thread: the
level at any instant is the stored level plus what the rate has produced since it was last
read, capped at the budget. And the instant a shortfall closes can be computed, which is what
`earliestFit` returns and what the evaluator parks until. A wait for tokens is a wake-up from
the clock, never a poll. The one deliberate timed re-check in the whole design is the memory
gate's, because a garbage collection announces nothing; even so the drain does not depend on
it, since a job finishing releases the next one whether or not the reading has moved.

### The debit lands at the grant

The same property of the algorithm fixes when a request may be charged: the rate decision and
the event are the same instant. A level charged at one instant for a request sent at a later
one charges a window the request did not fall in, and no care elsewhere repairs that.

The provider works the same way, which is the useful part. Bedrock deducts the input tokens
plus `max_tokens` from the token quota at the start of a request, throttles when a quota is
exceeded, enforces the request and token quotas together, and settles actual usage
afterwards. `ModelBinding.price()` reserves the input plus the output the request will ask
for, the same formula, so the local account mirrors the provider's exactly as long as the
local debit happens at the moment of dispatch. `tryTake` runs under the monitor's lock in the
same critical section as the check, the job's thread wakes holding the grant, and it goes on
to send without waiting on anything else.

### Head reservation: neither strict first-come nor free-for-all

Jobs that need several resources at once and hold them for a while are what queueing theory
calls the multiserver-job model. Grosof, Harchol-Balter and Scheller-Wolf show that serving
such jobs strictly first-come, with the head blocking everyone behind it, wastes capacity:
when the head does not fit, capacity sits idle behind it, and depending on the mix of jobs a
large share of it can be lost. Their ServerFilling policy recovers full throughput while
staying close to first-come order.

The repair that suits resources divisible into amounts is backfilling, from batch schedulers
for supercomputers. Lifka's EASY scheduler reserves capacity for the job at the head of the
queue and lets later jobs run only when they do not delay it. Mu'alem and Feitelson measured
the trade: the head is guaranteed, every job eventually becomes the head, and jobs behind the
head have no bound on their individual delay.

That is the policy `Admission` implements, one limit at a time: every limit is asked about
the asking job's amounts with the head's amounts on that limit set aside, which is why
`fits`, `tryTake` and `earliestFit` take two lists. The head is never overtaken, so Hoare's
no-overtaking property holds and the all-or-nothing starvation weakness is answered. Jobs
whose demands do not touch what the head is short of pass it at the first pass after they
arrive, so the line does not clog.

Metered limits need a sharper form of the same rule: while the head is short on a level,
nothing behind it may take from that level, because every unit taken postpones the head's
grant by that much. The memory gate's drain is such a level with no amounts to reserve, and
it needs the sharp rule for a second reason. The gate reads the clock and the heap afresh on
every question, while a pass over the line reads them once at its start, so a delay that
closes or a latch that clears while the evaluator is halfway down the line would release
whichever waiter it happened to be asking at that instant. So while the head is held on
memory, every resource-holding waiter behind it is held on memory too, with the head's
deadline, and the next wake-up starts again from the head. Without that rule a waiter deep in
the line would be released ahead of every older one at the drain's first instant.

### Why the connection pool cannot simply be made large enough

A tempting alternative is to make the HTTP pool so large that its limit never binds, leaving
the token budget as the only real gate. Little's law rules it out: the number of requests in
flight equals the rate at which they are admitted times how long each is held. A pool never
binds only while it exceeds, summed over every model, that rate times that holding time, and
the holding time is not bounded by the rate window: a call that writes a long answer holds
its connection for minutes. One pool serves every model and every other HTTP-using job at
once, so the pool would have to cover the fastest model's rate and the slowest call's
duration at the same time. No size can promise that.

### Holding a server while blocked downstream has a name

The literature on queueing networks with blocking separates designs by where a customer
waits when the next stage is full. Under blocking after service, the customer stays in the
server it just used, occupying it while serving nobody. Under blocking before service, a
customer may not occupy a server while the next stage cannot receive it. The two give
different throughput for the same network, and the difference is exactly the idle capacity
held by blocked customers.

Acquiring resources one after another is the first design: the HTTP place is the occupied
server and the empty token bucket is the full next stage. Granting the whole demand at once
is the second.

### Across a fleet of machines, the construct stays the same

Chandy and Misra solved fair acquisition of arbitrary sets of resources among distributed
processes without any central monitor, the drinking philosophers problem, by ordering the
processes in a graph whose edges change direction as resources are used, so that no process
waits forever. What it teaches here is its cost: fairness under retrying needs a property
that distinguishes the processes and changes over time, and ad-hoc release-and-retry has
none, which is why it livelocks.

Production systems take the other route. Doorman, a global client-side rate limiter, hands
out capacity as leases that expire, and a client whose lease lapsed behaves as if it had no
capacity. Kubernetes gang scheduling admits a group of pods all or nothing, and leaves the
group unscheduled so other work can proceed. Both are Havender's approach 2 with a queue.
Admission across machines would therefore keep this construct: it would add one more source
of wake-ups, a grant arriving from outside, and leases on held resources bounded by the job
timeout.

### The money wall refuses; it never stops

A spend cap on a workflow (`SpendGate`, implemented by `CostLedger`) is consulted after an
attempt's model bindings are resolved and priced and before the demand goes to admission. A
job whose reservation would carry the workflow past its cap is refused there, and the refusal
names the cap, the spend so far, the reservations its sibling jobs hold in flight and its own
reservation. It is deliberately no kill switch. A running call already committed its
reservation, and a cap that interrupted it would waste the tokens spent without saving the
money; refusing the next job saves the money and wastes nothing. An orchestrator that sees
the refusal stops submitting, which is how a whole workflow stops in its tracks without
anything mid-flight being cut.

Because the work runs as parallel as admission allows, staying under the cap is best effort,
and the gate says exactly how good the effort is. It judges a newcomer against what was spent
plus what is committed, where committed is every admitted, unfinished sibling's reservation,
so the fan-out itself never overshoots: a thousand files admitted at once are a thousand
reservations counted before any of them is priced. What remains is the gap between a
reservation and the invoice: the input as the runtime's tokenizer counts it against the
provider's count, and the output as declared against what the provider serves past the
declaration. That gap is bounded per call and does not grow with the fan-out.

### Why an LLM gateway's approach does not carry over

Gateways and proxies in front of model providers check each limit in turn and answer 429 on
the first miss; some reserve an estimated token count up front and reconcile afterwards, and
say plainly that their per-minute cap is not a hard bound within the minute. That is
Havender's approach 4, and it is right for a proxy, where a rejected request is the caller's
problem. Jobs here wait for as long as their timeout allows, by design, so admission has to
wait, and a wait for several resources at once is the monitor described above.

## What this rules out, and by which result

| Proposal | Closed by |
|---|---|
| Any fixed acquisition order, including reordering the current one | Havender approach 1 cures deadlock only; Reed and Kanodia: two sequential P operations are not a simultaneous-P |
| Sizing the connection pool so the permit never binds | Little's law: in-flight is rate times holding time, and holding time is unbounded by the window |
| Release-and-retry without a queue | Havender approach 3 is collective re-request; Chandy and Misra show what fairness costs when retry has no distinguishing property |
| Periodic sleep to re-check a limiter | Parnas accepts the prohibition on busy waiting; GCRA replaces the refill process with the clock, so a metered wait is a deadline |
| Fail fast on the first miss | Havender approach 4 needs a caller who can be told no |
| A blocking `acquire` on a single limiter, composed by the caller | A conjunctive guard needs a side-effect-free view of every account under one exclusion; Reed and Kanodia forbid waiting under the lock |
| Strict FIFO on the whole demand | Multiserver-job results: head-of-line blocking is not work-conserving |
| Letting any waiter take whatever fits | The all-or-none starvation caveat; EASY's head reservation is the fix |

## Sources

- Coffman, E. G., Elphick, M. J., Shoshani, A. "System Deadlocks." Computing Surveys 3(2), 1971, 67-78.
- Havender, J. W. "Avoiding deadlock in multitasking systems." IBM Systems Journal 7(2), 1968, 74-84.
- Patil, S. S. "Limitations and capabilities of Dijkstra's semaphore primitives for coordination among processes." MIT Project MAC, CSG Memo 57, 1971.
- Parnas, D. L. "On a solution to the cigarette smoker's problem (without conditional statements)." CACM 18(3), 1975.
- Reed, D. P., Kanodia, R. K. "Synchronization with eventcounts and sequencers." CACM 22(2), 1979.
- Hoare, C. A. R. "Monitors: an operating system structuring concept." CACM 17(10), 1974, 549-557. Also "Towards a theory of parallel programming", 1972; Brinch Hansen, P. Operating System Principles, 1973.
- Kessels, J. L. W. "An alternative to event queues for synchronization in monitors." CACM 20(7), 1977, 500-503.
- Andrews, G. R., Schneider, F. B. "Concepts and notations for concurrent programming." Computing Surveys 15(1), 1983.
- Cargill, T. "Specific Notification for Java Thread Synchronization." PLoP 1996.
- Chandy, K. M., Misra, J. "The drinking philosophers problem." ACM TOPLAS 6(4), 1984.
- ITU-T I.371; ATM Forum UNI 3.1: Generic Cell Rate Algorithm, virtual scheduling and continuous-state leaky bucket forms. Ritter, M., Tran-Gia, P. "Performance analysis of cell rate monitoring mechanisms in ATM systems", 1995.
- Little, J. D. C. "A proof for the queuing formula L = lambda W." Operations Research 9(3), 1961.
- Perros, H. G. "Queueing networks with blocking: a bibliography." SIGMETRICS PER 12(2), 1984. Onvural, R. O. "Survey of closed queueing networks with blocking." Computing Surveys 22(2), 1990. Balsamo, S., de Nitto Persone, V. "A survey of product form queueing networks with blocking and their equivalences." Annals of OR 48, 1994.
- Lifka, D. "The ANL/IBM SP scheduling system." JSSPP 1995. Mu'alem, A. W., Feitelson, D. G. "Utilization, predictability, workloads, and user runtime estimates in scheduling the IBM SP2 with backfilling." IEEE TPDS 12(6), 2001.
- Grosof, I., Harchol-Balter, M., Scheller-Wolf, A. "WCFS: a new framework for analyzing multiserver systems." Queueing Systems 102, 2022. Grosof, I., Scully, Z., Harchol-Balter, M., Scheller-Wolf, A. "Optimal scheduling in the multiserver-job model under heavy traffic." POMACS 6(3), 2022. Grosof, I., Harchol-Balter, M. "ServerFilling: a better approach to packing multiserver jobs." 2023.
- Ousterhout, J. K. "Scheduling techniques for concurrent systems." ICDCS 1982. Kubernetes documentation, gang scheduling and PodGroup scheduling.
- Google/YouTube Doorman, "Global Distributed Client-Side Rate Limiting", design document.
- AWS. "How tokens are counted in Amazon Bedrock"; quotas for the Bedrock runtime and Mantle endpoints.
