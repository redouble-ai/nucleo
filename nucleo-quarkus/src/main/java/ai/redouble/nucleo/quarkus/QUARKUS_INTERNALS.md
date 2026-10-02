# Inside the Quarkus extension

This page is for people working on the runtime itself. How to run Nucleo in a Quarkus
application is in [Quarkus](PACKAGE.md).

## What is here

| Type | Role |
|---|---|
| `QuarkusSecrets` | The runtime's credential store: `nucleo.credentials.<id>` answers the secret, `.user` and `.host` the other parts, read through `ConfigProvider` on every lookup. The runtime builds it by reflection through a no-argument constructor, so it holds no container reference. |
| `QuarkusEnvironment` | The `NucleoConfigurator` the extension registers under `META-INF/services`: it assigns `QuarkusSecrets` as the store and leaves every other default alone. |
| `QuarkusSettingsBinder` | Maps `nucleo.*` config properties onto the registered `Settings` classes: `nucleo.http.pool-size` binds `HttpSettings.poolSize` - the class minus `Settings` and the field, both kebab-cased - at startup, before the dispatcher runs. |
| `NucleoRuntime` | The application-scoped bean that binds the properties and starts the dispatcher on `StartupEvent`, subscribes the event log and the `CostLedger`, registers the ledger as the spend gate, and drains the dispatcher on shutdown. The lifecycle is process-scoped: a `StartupEvent` that finds the dispatcher already running does nothing, and shutdown runs only in a normal launch, never on a dev-mode reload or a test restart. The ledger is static, so every restart's bean exposes the instance that is subscribed. |
| `NucleoDatabase` | Registers the core `CountingDBResourceProvider` from `nucleo.database.*` when `max-concurrent` is set, observing `StartupEvent` after `NucleoRuntime` binds the properties, and unregisters it on shutdown. |
| `NativeSubstitutions` (in `ai.redouble.nucleo.quarkus.graal`) | GraalVM substitutions for the native image: dependencies that reach for a class the closed world does not carry (brotli in Apache HttpClient's content-compression, JBoss VFS in the reflections scan) are rebuilt without it, so no third-party native metadata is hand-rolled. |

## Why the lifecycle is process-scoped

A dev-mode live reload and a test-mode profile switch both stop and restart the application
in the same JVM. The dispatcher singleton, and the cost ledger held by `NucleoRuntime`, live
in the classloader that persists across those restarts, and the dispatcher does not restart
once shut down (its message bus does not reopen). So the dispatcher starts once and its
subscribers are registered once, and shutdown is skipped on a reload or a test restart.

## The deployment module

`nucleo-quarkus-deployment` carries the extension's build steps (`NucleoQuarkusProcessor`),
which run inside the Quarkus build of an application and never at application runtime:

- it indexes nucleo-core, the provider modules, the demo engine and the public skills jar
  with Jandex, so the reflection step can see their classes;
- it registers for reflection the transitive closure of the runtime's own serialized types
  (tools, artifacts, thinker inputs and outputs, reasoning, model specs, and every class
  carrying `@LLMDescription` or `@LLMRequired` members), never a third-party dependency's
  classes;
- it initializes at run time the singletons that start threads, and the provider and HTTP
  packages whose static initializers set up SDK clients or an SSL context;
- it carries the service files of providers, settings and configurators, and includes the
  skills and the providers' catalog fragments (`META-INF/nucleo/seed_models.json`) as resources,
  with a generated skill manifest in place of a classpath scan;
- it fails the build when a concrete artifact class lacks `@TypeAlias`.

## The native substitutions

`NativeSubstitutions` handles the two dependencies whose own code is native-hostile. Apache
HttpClient's `ContentCompressionExec` names a Brotli decoder in its constructor, so the
substitution re-declares that constructor to build the gzip and deflate registry the runtime
always gets, without the Brotli entry it never gets. org.reflections' `JbossDir`, a handler
for JBoss VFS URLs that matches no URL outside an application server, is deleted from the
image. No jars are added and no reflection or resource metadata is written for either.
