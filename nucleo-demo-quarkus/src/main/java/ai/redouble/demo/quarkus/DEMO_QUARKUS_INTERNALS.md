# Inside the Quarkus host

This page is for people working on the demo's Quarkus host itself: how to run it from a
checkout and the beans and configuration it wires. What the host is and how it differs from
the Spring one are in [The Quarkus host](PACKAGE.md).

## Running it

Dev mode, with live reload:

```
mvn -pl nucleo-demo-quarkus quarkus:dev
```

The packaged JVM jar:

```
mvn -pl nucleo-demo-quarkus package
java -jar nucleo-demo-quarkus/target/quarkus-app/quarkus-run.jar
```

A native image (GraalVM, `mvn -Pnative`):

```
mvn -pl nucleo-demo-quarkus -Pnative package
./nucleo-demo-quarkus/target/nucleo-demo-quarkus-runner
```

With no credential the process starts, `GET /status` reports every provider on the classpath
as unconfigured, and `POST /ask` is refused with the runtime's message naming what to provide.
Set one credential and the same question is answered, on `http://localhost:8080` by default.

## What Quarkus wires

- **Runtime**: the `nucleo-quarkus` extension (see
  [Quarkus](../../../../../../../../nucleo-quarkus/src/main/java/ai/redouble/nucleo/quarkus/PACKAGE.md))
  assigns `QuarkusSecrets` as the credential store, binds the `nucleo.*` properties onto the
  settings classes, and rides the dispatcher's lifecycle on the CDI container. The demo adds no
  runtime configuration of its own.
- **Credentials**: MicroProfile Config properties under `nucleo.credentials.*` in
  `application.properties`, each bound from the environment variable `AGENTS.md` names
  (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, and AWS's own `AWS_ACCESS_KEY_ID`,
  `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`, or a profile or the role the process runs under).
- **Beans**: `DemoProducers` hands the engine's plain objects to the CDI container as
  application-scoped singletons - `FileIndex`, `DemoCorpus`, `DemoCatalog`, and the shared
  `CatalogAdmin` over this host's `SessionCredentials` - so the JAX-RS `DemoResource` injects
  them. The engine knows nothing of either container.
- **Session credentials**: credentials pasted into the page are held by `SessionCredentials`,
  which writes them into `SessionConfigSource`, a MicroProfile config source read ahead of the
  deployment's own, so `QuarkusSecrets` sees them through `ConfigProvider` the way it sees any
  property. It is the Quarkus analog of the Spring host's runtime property source, and it works
  in the native image, where the source is registered through the service loader.
- **Startup**: `DemoStartup` reports the catalog on `StartupEvent` and, through
  `DemoHome.configure(DemoStartup.class, "application.properties")`, names the demo's catalog
  file to the runtime (see *Catalog*) before the report reads it.
- **Page files are served uncached** (`quarkus.http.static-resources.caching-enabled=false`
  in `application.properties`): the page's script and stylesheet change with every release,
  and Quarkus's default marks static files immutable for a day, so a browser would run an
  old script against new endpoints.

## Catalog

The demo's catalog file is shared behavior with the Spring host, in `DemoHome` in the engine:
each host keeps its catalog in its own module's resources, this one in
`nucleo-demo-quarkus/src/main/resources/models.json`, where every build carries it into the
Quarkus application and the native image (`quarkus.native.resources.includes` lists
`models.json`). `DemoHome` finds the module from where the class loader serves this module's
own `application.properties` (under `target/classes` in dev mode, inside the application's
jar), then `DemoStartup`'s class file, then the native executable, by Maven's standard layout:
the nearest `target` directory above it whose parent holds a `pom.xml`. The resource leads
because Quarkus's dev-mode class loader serves class files under a `quarkus:` scheme that
names no file. It names the file to the runtime through
`nucleo.models`, set in code, so an edit reaches the running process before any rebuild; a copy
running outside the module reads the build's copy, read-only. No file is written at a start:
the demo runs on the providers' shipped fragments until a person's action creates the file -
the Discover step writing the account's catalog into it, or a first edit in the models table
materializing the shipped defaults for the edit to land in, with no provider pinged. Under `quarkus:dev` the
same file is read and written, and a write to it triggers no live reload: dev mode watches its
configuration files and the resources it is told to, and `models.json` is neither, so a
session's pasted credentials survive an edit. The tests name a catalog of
their own, `src/test/resources/test-models.json`, through surefire, so a test run never seeds the
module's file. The catalog UI - connect a credential, run discovery, edit and
pin entries, order a grade - is the shared `CatalogAdmin` behind the routes `DemoResource`
maps; a refusal comes back as `{"message": ...}`, the shape the page reads, the same as the
Spring host.
