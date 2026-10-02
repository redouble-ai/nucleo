# Package: ai.redouble.nucleo.jdbc

For an application whose database access is a plain `javax.sql.DataSource`, this package
gives a tool a `java.sql.Connection` of its own for exactly as long as the tool runs. The
connection is checked out of the application's pool when the job is admitted, stays with the
job through every statement and transaction, and goes back to the pool when the job ends,
whether it succeeded, failed or timed out. The tool never opens or closes it.

`JdbcResourceProvider` is a database provider in the sense of
[Admission](../harness/admission/PACKAGE.md): the application registers it once under a
name with a limit, and admission lets at most that many jobs hold one of its connections at
a time. The Hibernate provider is in `nucleo-hibernate`
([Hibernate sessions](../../../../../../../../nucleo-hibernate/src/main/java/ai/redouble/nucleo/hibernate/PACKAGE.md)),
and the Spring provider in the Spring Boot starter.

## Registering the provider

The application registers the provider once at startup, before any job uses it, with the
name jobs will look it up by, its `DataSource`, and how many jobs may hold a connection at
once:

```java
JdbcResourceProvider db = new JdbcResourceProvider("default", dataSource, 20);
DBResourceProviders.register(db);
```

The pool behind the `DataSource` stays the application's: Hikari, Agroal, DBCP, UCP or a
container's JNDI pool. The provider never reads, sizes or tunes it. The limit is yours to
state, and it must not exceed the connections the pool can spare for jobs. When other code
shares the pool, such as web requests or a scheduler, and has used up every connection, a
job that was admitted waits inside the pool for as long as the pool's own checkout timeout
allows; a checkout that fails fails the job, and no retry can help it. The limit shows up in
the health snapshot as `db:<name>`.

## Using it in a tool

The tool looks the provider up by name, declares it, and takes its connection in `execute`:

```java
// in a job
JdbcResourceProvider db = DBResourceProviders.get("default", JdbcResourceProvider.class);
req.addProvider(db);
req.setRequiresTransaction(true);
...
Connection connection = resources.get(db);
```

With `setRequiresTransaction(true)` Nucleo begins a transaction before `execute`, commits it
after, and rolls it back when `execute` throws. Without it the connection is in auto-commit,
and the tool can run transactions of its own on it: `begin` turns auto-commit off, and
`commit` and `rollback` end the transaction and turn it back on, so one job can run several
transactions on its one connection. A job that declares `setReadOnly(true)` gets a connection
marked read-only.

The connection belongs to the provider: the tool must not close it, and Nucleo returns it
at the end.

## What happens at the end

- **Commit.** Once `commit` returns, the server has acknowledged it, and every other
  connection to the same server sees the data. So a job that depends on this one reads what
  it wrote, with no extra wait. A deployment that reads from a replica needs a provider of
  its own that waits for the replica.
- **Close.** When the job ends, an open transaction is rolled back, the connection's
  auto-commit and read-only settings are put back as the pool handed them out, and the
  connection is returned. It is returned even when putting the settings back fails, and that
  failure is reported afterwards.
- **Timeout.** When a job ignores its timeout, Nucleo aborts its connection with
  `Connection.abort`, on a thread of its own so the timeout never waits on the driver. The
  abort ends any transaction on the server. The connection is then handed back to the pool,
  which discards it; a pool that fails to do so is reported as that failure.

Closing or aborting twice does nothing the second time, a rollback with no transaction open
or after an abort does nothing, and a handle made by another provider is refused with
`IllegalArgumentException`. `JdbcResourceProviderTest` holds each of these rules, together with the
contract every provider passes (`DBResourceProviderContract` in nucleo-core's test-jar).
