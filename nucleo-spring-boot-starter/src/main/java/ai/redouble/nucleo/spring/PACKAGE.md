# Package: ai.redouble.nucleo.spring

To run Nucleo inside a Spring Boot application, add one dependency,
`ai.redouble:nucleo-spring-boot-starter`. Its auto-configuration wires what the application
would otherwise wire by hand:

- the job dispatcher, which runs every tool, agent and model call as a job, starts before the
  web server accepts its first request and drains after the last one;
- credentials and every runtime setting are read from the application's properties;
- every job's state changes show up in the application log;
- a cost ledger records what each workflow spends and is the dispatcher's spend gate, so a
  budget is enforced before a job takes any resource
  ([Observers, cost and the spend cap](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/observability/PACKAGE.md));
- the jobs that use the application's database can be counted, so no more of them run at
  once than the pool can serve;
- one command writes the deployment's model catalog.

## Starting the application

Start the application with `NucleoSpringApplication.run(Application.class, args)` in place of
`SpringApplication.run`. It runs the application exactly as `SpringApplication.run` would,
and adds the `discover` command described below. Everything else the starter does works under
either.

## Credentials

A provider asks the runtime for a credential by the id it declares, `anthropic-api-key` or
`aws-region`, and the starter's store, `SpringSecrets`, answers from the property of that
name under `nucleo.credentials`. The application binds the property the way it binds a
datasource password:

```yaml
nucleo:
  credentials:
    anthropic-api-key: ${ANTHROPIC_API_KEY:}
    aws-region: ${AWS_REGION:}
    "aws-access-key-id.user": ${AWS_ACCESS_KEY_ID:}
    aws-access-key-id: ${AWS_SECRET_ACCESS_KEY:}
```

The property itself is the credential's secret; `.user` and `.host` are its other two parts.
An environment variable, a profile file, a Vault path or a Kubernetes secret all bind it.

A part no property binds is read from the environment variable of the runtime's one rule
(`ANTHROPIC_API_KEY`, `AWS_REGION`, `AZURE_FOUNDRY_API_KEY` with `AZURE_FOUNDRY_API_KEY_HOST`
for the endpoint), part by part. So an application that binds nothing runs with the variables
exported, and a secret bound in the file composes with a host exported in the environment.
Where both are set, the bound part wins. A "not configured" refusal names both the property
and the variable. Every id and its parts are listed in
[Credentials](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/secrets/PACKAGE.md).

## Settings

Every runtime setting is a field on a settings class next to the code it governs, and in a
Boot application it binds from a `nucleo.*` property
([Settings and the configurator](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/PACKAGE.md)
lists them all):

```yaml
nucleo:
  http:
    pool-size: 2000
  model:
    mantle-lax-project: proj_...
```

The properties are bound before any bean is built, so a bean that touches the runtime while
it is constructed already sees them.

An application that prefers to set some of them in code writes its own `NucleoConfigurator`
and names its class with `-Dnucleo.env=<class>`. The starter's own configurator,
`SpringEnvironment`, stays on the classpath and is then ignored, and it is the one that
points the credential store at the properties above; so your configurator assigns
`SpringSecrets` to `SecretsSettings.secretsClass` itself when it wants credentials from the
properties. Two configurators registered under `META-INF/services` are refused by the
runtime, so an application never registers a second one. Properties are bound after the
configurator runs, so a property wins over code.

## Using the runtime from your beans

Submit work through `JobDispatcher.getInstance()`, or inject the starter's `NucleoRuntime`
bean: its `dispatcher()` is the same dispatcher, and its `ledger()` is the `CostLedger`
holding what every workflow has spent so far.

## The application's database

When jobs call the application's own repositories or services, the database pool has a size
and the jobs must not outnumber it. The starter registers a counter for that when told how
many jobs may use the database at once:

```yaml
nucleo:
  database:
    max-concurrent: 20   # jobs using a connection at once; unset registers nothing
    name: default        # the db:<name> health row, and the name jobs look the provider up by
```

With the bound set, the starter registers the core `CountingDBResourceProvider`
(`DatabaseSettings` in `ai.redouble.nucleo.harness.admission`), and a job declares the count
before it touches the database:

```java
CountingDBResourceProvider db = DBResourceProviders.get("default", CountingDBResourceProvider.class);
req.addProvider(db);
// execute() calls the application's own beans; Spring takes connections from its pool as usual
```

The counter needs nothing from the context, no datasource and no transaction manager, and
touches nothing in it: admission starts a job only while the count has room, and the job's
beans then take and return connections the way they always do
([Admission](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/admission/PACKAGE.md)).
The bound is the application's own statement and is never inferred from the pool: it must
leave the pool room for everything else that uses it.

### Holding one connection for the whole job

An application that wants each job on one connection from the moment it starts to its end
registers `SpringTransactionResourceProvider` itself, under a name of its own or as the
default in place of the counter (leave `max-concurrent` unset then):

```java
@Bean
SpringTransactionResourceProvider ordersDatabase(DataSourceTransactionManager transactionManager) {
    SpringTransactionResourceProvider provider = new SpringTransactionResourceProvider("orders", transactionManager, 20);
    DBResourceProviders.register(provider);
    return provider;
}
```

```java
SpringTransactionResourceProvider db = DBResourceProviders.get("orders", SpringTransactionResourceProvider.class);
req.addProvider(db);
req.setRequiresTransaction(true);
// execute() calls the application's own beans; they run on the job's held connection
```

The job's repositories, `JdbcTemplate` and `@Transactional` services then all run on the
connection the job was started with. It works over a `DataSourceTransactionManager`, and over
a `JpaTransactionManager` whose JPA provider is Hibernate; any other kind is refused when the
provider is built. One kind of transaction leaves the held connection: one that suspends the
transaction under way (`REQUIRES_NEW`) runs on a second connection from the pool, outside the
bound, so a job whose beans open such transactions holds two connections while they run and
the pool must have room for them. When other code has drained a shared pool, a job waits at
its start for as long as the pool's own checkout timeout allows.

## Writing the model catalog

Passing `discover` as the first program argument to an application started through
`NucleoSpringApplication` writes the deployment's model catalog instead of serving: the
context starts without the web server, on the credentials bound as above, every provider
those credentials configure is asked what the account can reach, every reachable entry is
pinged, the account's catalog is written, and the process exits with the discovery's code.
The remaining arguments go to the discovery
([Discovering an account's models](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/models/discovery/PACKAGE.md)).

It writes the file `--out` names, else the one `-Dnucleo.models` names:
`java -jar app.jar discover --out src/main/resources/models.json`. The build carries that
file onto the classpath, and the application finds it the way logging configuration is found:
the file `-Dnucleo.models` names, else every `models.json` on the classpath with the nearest
to the application taken. So a terminal, an IDE run configuration and a repackaged jar all
read the same catalog with nothing to pass. [Set up and run the
demo](../../../../../../../../AGENTS.md) walks the whole setup.

## Replacing a bean

An application that records usage in a database, exports metrics, or answers credentials
from a store Spring's environment does not reach declares its own `NucleoRuntime` or
`SpringSecrets` bean; the auto-configuration's bean of that type is then not created. An
application with a second datasource registers a provider for it in `DBResourceProviders`
itself, under a name of its own. The demo application, `nucleo-demo`, is a host that
replaces neither.

## How it works inside

The starter's classes, how the held connection is bound to a job's thread, and the tests that
pin all of it are in [Inside the Spring Boot starter](SPRING_INTERNALS.md), for those working
on the runtime itself.
