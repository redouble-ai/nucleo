# Package: ai.redouble.nucleo.quarkus

To run Nucleo inside a Quarkus application, add one dependency, `ai.redouble:nucleo-quarkus`.
It is the Quarkus counterpart of the
[Spring Boot starter](../../../../../../../../nucleo-spring-boot-starter/src/main/java/ai/redouble/nucleo/spring/PACKAGE.md),
and it wires the same things:

- the job dispatcher, which runs every tool, agent and model call as a job, starts when the
  container starts, before the HTTP router accepts a request, and drains at shutdown;
- credentials and every runtime setting are read from MicroProfile Config, so from
  `application.properties` and everything Quarkus reads configuration from;
- every job's state changes show up in the application log;
- a cost ledger records what each workflow spends and is the dispatcher's spend gate
  ([Observers, cost and the spend cap](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/observability/PACKAGE.md));
- the jobs that use the application's database can be counted;
- the application builds to a native image with no native configuration of its own.

It needs `quarkus-arc` and MicroProfile Config and no web framework, so it serves a REST
application and a batch process the same way. It is a Quarkus extension: this runtime module
and a deployment module, `nucleo-quarkus-deployment`, whose build steps make the runtime work
in a native image.

## Credentials

A provider asks the runtime for a credential by the id it declares, `anthropic-api-key` or
`aws-region`, and the extension's store, `QuarkusSecrets`, answers from the property of that
name under `nucleo.credentials`. The application binds the property the way it binds
`quarkus.datasource.password`:

```properties
nucleo.credentials.anthropic-api-key=${ANTHROPIC_API_KEY:}
nucleo.credentials.aws-region=${AWS_REGION:}
nucleo.credentials.aws-access-key-id.user=${AWS_ACCESS_KEY_ID:}
nucleo.credentials.aws-access-key-id=${AWS_SECRET_ACCESS_KEY:}
```

The property itself is the credential's secret; `.user` and `.host` are its other two parts.
A system property, a profile, or a config source of the deployment's own all bind it.

A part no property binds is read from the environment variable of the runtime's one rule
(`ANTHROPIC_API_KEY`, `AWS_REGION`, `AZURE_FOUNDRY_API_KEY` with `AZURE_FOUNDRY_API_KEY_HOST`
for the endpoint), part by part, so an application that binds nothing runs with the variables
exported, and a bound part always wins. A "not configured" refusal names both the property
and the variable. Every id and its parts are listed in
[Credentials](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/secrets/PACKAGE.md).

## Settings

Every runtime setting is a field on a settings class next to the code it governs, and in a
Quarkus application it binds from a `nucleo.*` property
([Settings and the configurator](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/PACKAGE.md)
lists them all). The properties are bound when the container starts, before the dispatcher
runs, and a property wins over whatever a configurator set:

```properties
nucleo.http.pool-size=2000
nucleo.model.mantle-lax-project=proj_...
```

The spelling of every property is the same as in a Spring Boot application, so a setting
reads the same in either host. An application that names its own configurator with
`-Dnucleo.env` replaces the extension's `QuarkusEnvironment`, which is the one that points the
credential store at the properties above; its configurator then assigns `QuarkusSecrets` to
`SecretsSettings.secretsClass` itself when it wants credentials from the properties.

## Using the runtime from your beans

Submit work through `JobDispatcher.getInstance()`, or inject the extension's `NucleoRuntime`
bean: its `dispatcher()` is the same dispatcher, and its `ledger()` is the `CostLedger`
holding what every workflow has spent so far. The dispatcher starts before any `StartupEvent`
observer of the application's own at default priority, so such an observer may submit a
first job.

In dev mode a live reload, and in tests a switch of test profile, restarts the application
inside the same JVM. The dispatcher keeps running across those restarts, and is shut down
only when a normal run of the application ends.

## The application's database

When jobs call the application's own Panache repositories, an injected `EntityManager`, a
`@Transactional` bean or an Agroal `DataSource`, the extension can count the jobs using the
database at once, so they never outnumber the pool:

```properties
nucleo.database.max-concurrent=20
nucleo.database.name=default
```

With the bound set, the extension registers the core `CountingDBResourceProvider` when the
container starts, and a job declares it before it touches the database, exactly as in a
Spring Boot application
([Spring Boot](../../../../../../../../nucleo-spring-boot-starter/src/main/java/ai/redouble/nucleo/spring/PACKAGE.md),
[Admission](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/admission/PACKAGE.md)).
Without `max-concurrent` nothing is registered.

## Native image

Because `nucleo-quarkus` is a Quarkus extension, a native build of the application produces
a working native image with no native configuration from the application: the extension
registers what the runtime reaches by reflection, the resources it reads, and the parts that
must start at run time.

The one rule the extension keeps is that no third-party dependency's native metadata is
written by hand: a dependency reaches the native image through its own Quarkus extension, or
stays off the native build. So the direct Anthropic provider, whose Kotlin SDK has no native
support, runs on the JVM only, as does Claude on Bedrock through the Anthropic SDK; a native
image reaches Claude through `nucleo-provider-bedrock`, on the AWS SDK. The AWS SDK's native
support comes from its own Quarkus extension, `io.quarkiverse.amazonservices:quarkus-amazon-bedrockruntime`,
which an application calling Bedrock from a native image adds to its own dependencies; without
it the native build fails on the SDK's optional CRT paths.
[The Quarkus host](../../../../../../../../nucleo-demo-quarkus/src/main/java/ai/redouble/demo/quarkus/PACKAGE.md)
of the demo is built both ways.

## How it works inside

The extension's classes, the deployment module's build steps and the native substitutions are
in [Inside the Quarkus extension](QUARKUS_INTERNALS.md), for those working on the runtime
itself.
