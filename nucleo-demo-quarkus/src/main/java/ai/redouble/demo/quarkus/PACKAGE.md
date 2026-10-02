# Nucleo demo (Quarkus)

This is the demo served by a Quarkus application instead of a Spring Boot one. It serves the
same engine, `nucleo-demo-engine`: the same page, the same nine steps, the same endpoints,
answered by the same code, so everything [the demo page](../../../../../../../../nucleo-demo/src/main/java/ai/redouble/demo/PACKAGE.md)
describes holds here unchanged. It runs on the JVM, and it also compiles to a native image, a
standalone executable built ahead of time by GraalVM, which the Spring host does not.

It is the example to copy for running Nucleo inside a Quarkus application of your own.

## What the host adds

The Quarkus host contains no demo logic, only the wiring that hands the engine to Quarkus:

- **The runtime** comes from the `nucleo-quarkus` extension
  ([Quarkus](../../../../../../../../nucleo-quarkus/src/main/java/ai/redouble/nucleo/quarkus/PACKAGE.md)),
  which starts and stops the dispatcher with the application and reads Nucleo's settings and
  credentials as ordinary Quarkus configuration.
- **Credentials** are properties under `nucleo.credentials.*` in `application.properties`,
  each bound from the environment variable [the setup](../../../../../../../../AGENTS.md)
  names, the way any Quarkus property is bound
  ([Credentials](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/secrets/PACKAGE.md)).
  AWS needs no variable when a profile or the role the process runs under provides it.
- **The engine's objects** - the index, the corpus, the catalog, and the shared logic behind
  the catalog endpoints - are handed to the container by `DemoProducers`, one of each for the
  application, and the endpoints of `DemoResource` use them. The engine itself knows nothing
  of Quarkus or Spring.
- **Credentials pasted into the page** are held by `SessionCredentials` in a configuration
  source that Quarkus reads before the deployment's own, so the runtime finds them the way it
  finds any other property. It works in the native image too.
- **At startup** `DemoStartup` names the demo's catalog file to the runtime, this module's
  `src/main/resources/models.json`, and reports the catalog the process runs on, as the Spring
  host does with its own module's file. Every build carries it into the Quarkus application and
  the native image, and the page's Discover button writes it.

A refusal from any endpoint reaches the page as `{"message": ...}` carrying the rule that was
broken, the same shape the Spring host sends.

## How it works inside

How to run the host from a checkout, in dev mode, as a JVM jar or as a native image, and the
beans and configuration sources it wires are in
[Inside the Quarkus host](DEMO_QUARKUS_INTERNALS.md), for those working on the demo itself.
