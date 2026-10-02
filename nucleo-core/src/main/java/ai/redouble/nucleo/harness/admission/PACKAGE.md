# Package: ai.redouble.nucleo.harness.admission

Admission is where a job waits for what it declared in its requirements
([The job runtime](../PACKAGE.md)), and this package holds the limits it waits on: a
model's tokens per minute, the shared HTTP pool, a database's connections, the heap, and any
limit an application adds.

The problem it solves is the one a job with several needs creates. A tool that calls a model
and writes the answer to a database needs tokens on the model, a place in the HTTP pool and a
database connection. If it takes them one at a time, it holds the first while it waits for
the next. When the model's tokens run out for the minute, every job in the line holds an HTTP
place while it waits for tokens, the pool empties, and a job that needed no tokens at all
waits for a connection nobody is using. Everything looks healthy in isolation and nothing
moves.

## One grant for the whole list

A job's needs are collected into one `Demand`: every limit it must hold at once, with how
much of each. `Admission`, one per dispatcher, takes the whole demand in a single step or
none of it. A job whose demand does not fit waits holding nothing, and when it is granted it
has everything at once. You never call `Admission` yourself; the dispatcher does, from what
`getRequirements()` declared.

What the job was granted comes back in two ways when it ends:

- **Held things return when the work ends.** A database connection, a place in the HTTP
  pool, a slot on a server that takes so many requests at once: these are free again the
  moment the job is done.
- **Things spent against time return with time.** Tokens per minute and requests per second
  were spent when the request was sent, and the provider has counted them. They come back as
  the window moves on, whatever the job does.

A job that was granted and then never ran, because it was interrupted or a database
connection could not be opened for it, gives everything back, the spent kind included.

## Who goes first

Waiting jobs form one line in the order they arrived, and the job at the head is protected:
a job behind it may take only what is left over after what the head is waiting for. So a job
that needs a lot is never starved by a stream of small ones that keep taking what frees up,
and it becomes the head in its turn. A job whose needs do not touch what the head is short of
goes ahead at once: while the head waits for tokens on one model, a job that only needs a
database connection is granted immediately.

Nobody polls. The line is looked at again whenever something changes (a job finishes and
gives back what it held, a new job arrives, a limit grows), and when the head is only short
of tokens, at the exact moment the tokens will have refilled.

## The limits a job waits on

Every limit is an account: a counter that admission can ask "would this amount fit now?" and
then debit. An account never makes anyone wait itself; the waiting is admission's. The
accounts a job meets:

- **A model's token budget.** Each catalog entry bounded by a quota gets a
  `TokenBucketRateLimiter`: tokens and requests per minute, from the entry's `tpm` and `rpm`.
  It refills with the clock, slows itself down when the provider answers 429, speeds up again
  after a run of successes, and takes new limits in place when the provider reports them.
- **A model served from your own machine.** An entry that declares `max_concurrent` gets a
  `ModelGate` instead: one slot per request in flight.
- **The HTTP pool.** `HttpConnectionGate`, named `http`: one place per job that makes HTTP
  calls, as many places as the pool has connections ([HTTP from a tool](../../http/PACKAGE.md)).
- **A database.** A `DatabaseGate` per registered database provider, named `db:<name>`: one
  permit per job that uses the database (below).
- **A web service's rate.** An `ElasticWindowRateLimiter` allows so many requests per window.
  When the service pushes back it widens its own window, and after repeated failures at its
  slowest it stops sending for a cool-down and then lets one request probe whether the
  service is back.
- **The heap**, `MemoryPressureGate`, below.
- **Your own limits**, below.

## The heap

No job can say in advance how much heap it will use, so memory cannot be booked like a
connection. The memory gate watches the heap instead and is asked before anything else,
every time a job that declared resources is about to be granted:

- At 95% heap it grants nothing to jobs that declared resources, and it keeps refusing until
  the heap is back under 90%.
- The jobs it held back then start one at a time. The spacing is 50 milliseconds while the
  heap is under 80% and grows up a curve to 30 seconds at 95%. When the heap grew between
  one start and the next, the spacing is multiplied further, so a heap that is filling fast
  slows the line more than one that is merely full. A job finishing also lets the next one
  start, since a finished job is a moment when the heap may have room.
- A job that arrives when nobody is waiting on memory starts at once, at any heap below 95%.
- Orchestrators never meet the gate: they hold nothing, and every tool they call meets it on
  its own.

## A limit of your own

A tool that calls a service with a rate limit of its own declares the limit, and admission
waits on it with the job's other needs. For a service that allows five requests a second,
subclass `ElasticWindowRateLimiter` with the window and the count:

```java
public class GeocoderRateLimiter extends ElasticWindowRateLimiter {
    GeocoderRateLimiter() {}
    @Override protected long getBaseWindowMs() { return 1_000; }
    @Override protected int getMaxRequests() { return 5; }
}
```

and have the tool ask for a slot on the one instance the factory keeps per class:

```java
private static final GeocoderRateLimiter GEOCODER = RateLimiterFactory.getInstance().getRateLimiter(GeocoderRateLimiter.class);

@Override
public JobRequirements getRequirements() {
    JobRequirements requirements = new JobRequirements();
    requirements.requireRateLimiter(GEOCODER, null);
    return requirements;
}
```

The job takes one slot of the window; a job that makes several calls to the service in a
row makes them on that one slot. A window implies a place in the HTTP pool, so the job need
not ask for one. When the job succeeds the window hears of it and relaxes over time; when it
fails with an `ExternalServiceException` the window counts that as pushback and slows down.

For a cap on how many jobs may do something at once, such as run a heavy local process,
subclass `CountingGate` with a capacity and a name. A gate's slots are held for the work and
return when the job ends:

```java
public class RenderSlots extends CountingGate {
    RenderSlots() { super(4); }
    @Override public String limiterName() { return "render"; }
}
```

Anything else implements `RateLimiter<T>`, where `T` is what a job asks for: `Integer` for a
count of tokens, `Void` for a single slot, or a key that routes the request to one of several
buckets. Accounts of your own take part in the line, the head's protection and the giving
back exactly as the built-in ones do.

## A spend cap comes first

Before a job's demand goes to admission, its model reservations are priced and shown to
every registered `SpendGate`, the money limit on a workflow. The cost ledger is one: a job
whose reservation would take its workflow past its cap is refused there, before it waits for
anything, and an orchestrator that sees the refusal stops submitting. Nothing already running
is stopped ([A dollar cap on a workflow](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/cap/PACKAGE.md)).

## Database connections

A database joins admission through a `DBResourceProvider`: an object the application
registers once, which exposes its limit as an account (`admission()`) and hands a job its
connection (`acquire`). Admission takes one permit on the provider's gate with the rest of
the job's demand, and only then does Nucleo ask the provider for the job's connection. The
permit is admission's and returns when the job ends.

The limit is yours to state: how many jobs may use this database at once. It must leave the
connection pool room for everything else that uses it, web requests and schedulers included.
The pool itself is always the application's: no provider reads, sizes or tunes it.

### The default: a count

The simplest provider only counts. `CountingDBResourceProvider` lets so many jobs use the
database at once, and the job does its data access however the application already does it:
repositories, `JdbcTemplate`, an `@Transactional` service, a JTA-managed `EntityManager`. The
application's own stack takes a connection from its pool whenever it needs one and returns it
the same way; the provider hands the job nothing and depends on no database API.

In Spring Boot or Quarkus you turn it on with two properties, and the integration registers
it at startup and removes it when the application stops:

```properties
nucleo.database.max-concurrent=20
nucleo.database.name=default
```

Without `max-concurrent` nothing is registered: Nucleo never guesses a limit from the pool's
size. Elsewhere, `DBResourceProviders.registerDefault()` registers it from `DatabaseSettings`.
A job then looks it up and declares it:

```java
CountingDBResourceProvider db = DBResourceProviders.get("default", CountingDBResourceProvider.class);
req.addProvider(db);
// execute(): repositories, JdbcTemplate, an @Transactional service, a JTA-managed EntityManager
```

A job whose stack takes two connections at once counts once and holds two: a transaction
that suspends the one under way (`REQUIRES_NEW`), or a request-scoped connection beside a
transaction. The pool must have room for the second.

### A provider that holds the connection

When the job should hold one connection from the moment it is admitted to its end, register
a provider that checks the connection out for it. For a plain `DataSource` that is
`JdbcResourceProvider`, registered once at startup before any job uses it:

```java
DataSource dataSource = ...;   // the host's pool: Hikari, Agroal, DBCP, UCP, JNDI
JdbcResourceProvider orders = new JdbcResourceProvider("orders", dataSource, 20);
DBResourceProviders.register(orders);
```

A job names it, declares it, and gets the connection after the grant:

```java
JdbcResourceProvider orders = DBResourceProviders.get("orders", JdbcResourceProvider.class);
req.addProvider(orders);
req.setRequiresTransaction(true);
...
Connection connection = resources.get(orders);
```

With `requiresTransaction` Nucleo begins a transaction before `execute`, commits after it
and rolls back when it throws; without it the connection stays in auto-commit and the job
runs its own transactions on it. The same holds for `HibernateResourceProvider`, which hands
the job a Hibernate `Session`, and for `SpringTransactionResourceProvider` in the Spring Boot
starter, which binds the connection to the application's own transaction manager. The pages
[JDBC connections](../../jdbc/PACKAGE.md) and
[Hibernate sessions](../../../../../../../../../nucleo-hibernate/src/main/java/ai/redouble/nucleo/hibernate/PACKAGE.md)
say what each does at commit, close and timeout.

`DBResourceProviders` is where jobs find providers. A tool is built by reflection from its
parent alone, so it cannot be handed a provider; it names its database instead, the way code
names a JNDI resource. Each name is one database: registering a second provider under a name
already taken is refused, and looking up a name that is not registered lists the ones that
are. A counting provider and a holding one can sit side by side under different names, each
with its own `db:<name>` limit.

A data layer of your own implements `DBResourceProvider<T>`: a `DatabaseGate` for
`admission()`, and the lifecycle verbs over its own session type. `DBResourceProviderContract`,
shipped in nucleo-core's test-jar, is the test every provider passes, against a real
in-process database behind a real pool. `AbstractHibernateResourceProvider` in
`nucleo-hibernate` is the base for a Hibernate data layer that hands jobs something other
than the `Session` itself.

## Seeing who waits for what

`Admission` publishes a `LimiterEvent` for every step a job takes through it: granted at
once, held on an account it was short of, granted after the hold, refused, released. The
health snapshot the observability package prints shows every account under its name: the
model's id, `http`, `db:<name>`, your own gate's name. Each job's context records how long it
waited in total, under `admission_wait_ms`, and how long each account held it, under
`wait_times` ([Observers, cost and the spend cap](../observability/PACKAGE.md)).

## How it works inside

Every type in the package, the rules of the monitor, the events it emits, the account
contract, and the exact behavior of the memory gate, the token bucket, the elastic window
and the factory, each with the tests that hold it, are in
[Inside admission](HARNESS_ADMISSION_INTERNALS.md), for those working on the runtime itself.
Why admission is built this way, and what each simpler design runs into, is in
[Why admission has this shape](ADMISSION.md).
