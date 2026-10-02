# Package: ai.redouble.nucleo

A deployment of Nucleo has a handful of things it may want to change: how many HTTP
connections the process opens at once, where credentials come from, how many MCP server
processes may run side by side, which model catalog to read. Each of them is a public field
on a settings class that sits beside the code it governs, with the value it ships with
written inline. So the question "where do I tune X?" always has the same answer: in the
settings class of the package that owns X.

Your application changes the fields it cares about in one place. In a plain Java
application that place is one class you write, the configurator. In a Spring Boot or
Quarkus application it is usually the application's properties file, and you write no
class at all.

## Reading and changing a setting

`Settings.get(HttpSettings.class)` returns the one instance of `HttpSettings` in the
process, and its fields are plain public fields:

```java
Settings.get(HttpSettings.class).poolSize = 2000;
```

Each field's javadoc says when the runtime reads it. Many are read once, when the part they
govern is first built (the HTTP pool reads `poolSize` when it builds), so a setting is
changed at startup, before any work runs. That is what the configurator is for.

## The configurator

A configurator is a class implementing `NucleoConfigurator`. Its one method, `configure()`,
assigns the settings that differ from the shipped values:

```java
public class AcmeConfigurator implements NucleoConfigurator {
    @Override public void configure() {
        Settings.get(SecretsSettings.class).secretsClass = AcmeSecrets.class;
        Settings.get(HttpSettings.class).poolSize = 2000;
    }
}
```

The runtime finds it by itself and runs it once, the first time anything anywhere calls
`Settings.get`, before that call returns. It looks in this order:

1. The class named by the system property `-Dnucleo.env`, which wins over any registration.
2. Else the single class listed in `META-INF/services/ai.redouble.nucleo.NucleoConfigurator`.
3. Else none: the shipped values stand, and the log says so.

Three rules follow from there being one configurator per deployment:

- **Two registrations are refused at startup.** The refusal names both classes and the
  property that picks one, because letting the classpath order choose would be a
  configuration nobody wrote.
- **A configurator that throws stops the runtime.** Every later `Settings.get` rethrows
  that first failure, so nothing runs on half-applied configuration.
- **A library never ships a configurator.** A library ships its settings classes with their
  defaults; only the application ships the one configurator.

The Spring Boot starter and the Quarkus extension each register a configurator of their own,
which points the credential store at the application's properties. Naming your own class
with `-Dnucleo.env` replaces theirs, so yours then sets the credential store as well
([Spring Boot](../../../../../../../nucleo-spring-boot-starter/src/main/java/ai/redouble/nucleo/spring/PACKAGE.md),
[Quarkus](../../../../../../../nucleo-quarkus/src/main/java/ai/redouble/nucleo/quarkus/PACKAGE.md)).

## Settings as properties in Spring Boot and Quarkus

In a Spring Boot or Quarkus host, every field of every registered settings class binds from
a property under `nucleo.`: the settings class's simple name without `Settings`, then the
field, both written in kebab case. `HttpSettings.poolSize` is `nucleo.http.pool-size`:

```yaml
nucleo:
  http:
    pool-size: 2000
```

A field may be a primitive or its box, a `String`, or a `Class`, which binds from a fully
qualified class name. Properties are bound after the configurator runs, so where both set a
field the property wins.

## What you can tune

| Setting | Property | What it changes | Shipped value |
|---|---|---|---|
| `SecretsSettings.secretsClass` | `nucleo.secrets.secrets-class` | the store every credential is read from ([Credentials](secrets/PACKAGE.md)) | `EnvironmentSecrets` |
| `ModelSettings.backendClass` | `nucleo.model.backend-class` | where the model catalog is read from ([The catalog, grades and the picker](harness/models/PACKAGE.md)) | `JsonModelsBackend` |
| `ModelSettings.pickerClass` | `nucleo.model.picker-class` | which picker turns a grade into a model | `DefaultModelPicker` |
| `ModelSettings.mantleLaxProject` | `nucleo.model.mantle-lax-project` | the Bedrock Mantle project whose data retention mode is `provider_data_share`; models that require data sharing are served only under it | none |
| `ModelSettings.mantleStrictProject` | `nucleo.model.mantle-strict-project` | the Bedrock Mantle project whose data retention mode is `none`; when set, every Mantle request for a model that does not require data sharing is pinned to it | none |
| `HttpSettings.poolSize` | `nucleo.http.pool-size` | the size of the one shared HTTP connection pool ([The HTTP connection pool](http/PACKAGE.md)) | 1000 |
| `McpSettings.stdioPoolMax` | `nucleo.mcp.stdio-pool-max` | how many MCP server subprocesses may be in use at once, process-wide ([Calling MCP servers](mcp/PACKAGE.md)) | 20 |
| `McpSettings.stdioMaxConcurrentPerEndpoint` | `nucleo.mcp.stdio-max-concurrent-per-endpoint` | the same, for one server | 10 |
| `McpSettings.stdioIdleTimeoutSeconds` | `nucleo.mcp.stdio-idle-timeout-seconds` | how long an idle MCP subprocess is kept before it is stopped | 300 |
| `McpSettings.descMaxChars` | `nucleo.mcp.desc-max-chars` | the longest description of a remote MCP tool shown to a model | 4096 |
| `DatabaseSettings.name` | `nucleo.database.name` | the name the application's database is registered under ([Admission](harness/admission/PACKAGE.md)) | `default` |
| `DatabaseSettings.maxConcurrent` | `nucleo.database.max-concurrent` | how many jobs may use the application's database at once; unset registers no database | unset |
| `AnthropicSettings.connectionPoolSize` | `nucleo.anthropic.connection-pool-size` | the idle connections kept by the Anthropic SDK client, in `nucleo-provider-anthropic` | 200 |

## Seeing the whole configuration

`NucleoConfigurator.printAll()` returns every registered settings class with every field's
current value, one line each, including the settings of jars other than nucleo-core. Logging
it at startup shows what the deployment actually runs with.

## Settings of your own library

A library with knobs of its own follows the same pattern: one subclass of `Settings` per
package that owns knobs, named `<Area>Settings`, with plain mutable public fields and their
defaults inline, and one line naming it in `META-INF/services/ai.redouble.nucleo.Settings`.
That line is what lets `printAll()` list it and what lets the Spring Boot and Quarkus hosts
bind its properties.

## Numbers that are not settings

A number that is part of a contract is a constant, and no setting changes it:
`EmbeddingsClient.DIMENSIONS` (stored vectors are that wide, and changing it is a
migration), `SingleObjectiveThinker.DEFAULT_MAX_ITERATIONS` (the per-instance
`setMaxIterations` is the real knob), `JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS` (a caller
of `shutdown(ms)` passes its own number).

## How it works inside

The tests that pin the discovery rules and `printAll` are listed in
[Inside settings](NUCLEO_INTERNALS.md), for those working on the runtime itself.
