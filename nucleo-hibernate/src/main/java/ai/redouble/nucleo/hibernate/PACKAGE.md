# Package: ai.redouble.nucleo.hibernate

For a Hibernate application, this module gives a tool a Hibernate `Session` of its own for
exactly as long as the tool runs. The session is opened from the application's own
`SessionFactory` when the job is admitted, holds one database connection from then until the
job ends, and is closed when the job ends, whether it succeeded, failed or timed out. The
tool never opens or closes it.

`HibernateResourceProvider` is a database provider in the sense of
[Admission](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/admission/PACKAGE.md):
the application registers it once under a name with a limit, and admission lets at most that
many jobs hold one of its sessions at a time. For a plain `DataSource` the same role is
played by [the JDBC provider](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/jdbc/PACKAGE.md).

## Registering the provider

The application registers the provider once at startup, with the name jobs will look it up
by, its `SessionFactory`, how many jobs may hold a session at once, and how long a commit may
take to complete (below):

```java
HibernateResourceProvider db = new HibernateResourceProvider("default", sessionFactory, 20, Duration.ofSeconds(30));
DBResourceProviders.register(db);
```

The pool behind the factory stays the application's, and the provider never reads or tunes
it. The limit is yours to state, and it must not exceed the connections the pool can spare
for jobs. It shows up in the health snapshot as `db:<name>`.

## Using it in a tool

The tool looks the provider up by name, declares it, and takes its session in `execute`:

```java
// in a job
HibernateResourceProvider db = DBResourceProviders.get("default", HibernateResourceProvider.class);
req.addProvider(db);
req.setRequiresTransaction(true);
...
Session session = resources.get(db);
```

With `setRequiresTransaction(true)` Nucleo begins a transaction before `execute`, commits it
after, and rolls it back when `execute` throws. A job that declares `setReadOnly(true)` gets
a session whose entities are read-only by default, on a connection marked read-only.

## Why the session keeps its connection

Left to its default, a resource-local Hibernate session, one whose transactions are plain
JDBC transactions, hands its connection back to the pool after every transaction and takes
a new one for the next. For a job that would mean
going back to the pool, and possibly waiting there, at every commit, while it holds its
admission permit. So the provider opens each session in
`PhysicalConnectionHandlingMode.DELAYED_ACQUISITION_AND_HOLD` and takes the connection out
at once. The mode is set on that session alone: the application's factory, and every session
other code opens from it, stay as they are.

## What happens at the end

- **Commit.** The provider registers a callback on each transaction and, after the commit,
  waits for the transaction to report that it completed, for at most the completion timeout
  given at registration. Past it, the job fails, unless the transaction already reached a
  final state. On an ordinary synchronous commit the transaction has completed by the time
  `commit` returns, so there is nothing to wait for; the wait is there for a stack whose
  completion comes later. A rollback, a close or an abort also ends the wait, so nothing
  waits on a transaction that is gone.
- **Close.** When the job ends, an active transaction is rolled back, the connection's
  read-only setting is put back as the pool handed it out, and the session is closed, which
  returns the connection. It is closed even when any of those steps fails, and that failure
  is reported afterwards.
- **Timeout.** When a job ignores its timeout, Nucleo aborts the connection the session
  holds with `Connection.abort`, on a thread of its own. It leaves the session itself alone,
  since the job's thread may still be inside it. A close after that only closes the session;
  a pool that fails to reset the severed connection is reported as that failure.

Closing or aborting twice does nothing the second time, and a handle made by another
provider is refused with `IllegalArgumentException`. `HibernateResourceProviderTest` holds
each of these rules, together with the contract every provider passes
(`DBResourceProviderContract` in nucleo-core's test-jar).

## A data layer with its own session wrapper

An application that wraps Hibernate's session in a type of its own can hand jobs that type.
`AbstractHibernateResourceProvider<T>` does everything above, and a subclass says what a job
unwraps: `expose(session)` builds the job's object around the session, and `closing(value)`
runs the application's own end-of-work bookkeeping on it before the session closes (by
default nothing). `HibernateResourceProvider` is the subclass that exposes the `Session`
itself.

The base class receives the factory as a supplier and asks for it at every `acquire`, so an
application that creates its providers before its persistence layer is up, as a web
application may, is served the factory that is current when each job starts.
