# Nucleo demo on Quarkus

The same demo as `nucleo-demo`, hosted by Quarkus instead of Spring Boot over the identical
engine (`nucleo-demo-engine`). It shows the runtime running under either host with no change to
the runtime itself.

## Running it (every OS, including macOS)

```
mvn -f nucleo-demo-quarkus/pom.xml quarkus:dev
# or the built jar:
mvn -f nucleo-demo-quarkus/pom.xml package
java -jar nucleo-demo-quarkus/target/quarkus-app/quarkus-run.jar
```

The JVM demo runs on any operating system, macOS included, and reads PDF and Office files
locally with Apache PDFBox and POI (the `nucleo-demo-poi` add-on, active in the default `jvm`
profile).

## Native image

```
mvn -f nucleo-demo-quarkus/pom.xml -Pnative package
```

The native build runs on any host, macOS included. Selecting `-Pnative` deactivates the `jvm`
profile, so PDFBox and POI, and the AWT they pull in, are absent from the native dependency
graph. The image falls back to the engine's toolkit-free reader: Office documents are read as
the zip-of-XML they are, and PDFs are shipped whole to the model tier rather than rendered
locally. A native executable is built for the OS and CPU it is compiled on and is not portable
across operating systems.

## Native dependency policy (HARD RULE, no exceptions)

**Native support for a third-party dependency is never hand-rolled.** No hand-written
`reflect-config.json`, `resource-config.json` or `reachability-metadata.json` for a dependency,
and no extra jars added to force one to link. For any dependency the native image needs, there
are exactly two options:

1. **Use its existing Quarkus or Quarkiverse extension.** Check for one first. The AWS SDK is
   carried by `quarkus-amazon-bedrockruntime`; Kotlin reflection by `quarkus-kotlin`.
2. **Do not use the dependency in the native build.** Keep it JVM-only behind a maven profile,
   the way `nucleo-demo-poi` keeps POI and PDFBox out of native.

This is why the native model tier calls Bedrock through the AWS SDK (extension-backed) rather
than the okhttp/Kotlin `anthropic-java` and `openai-java` SDKs, which have no extension and so
stay JVM-only. The rule bars third-party metadata only: the runtime's own service files travel
through the `nucleo-quarkus` extension's build steps, and this host registers the demo
engine's own `ExtractionTools` service and its reflective constructor in its own
`META-INF/native-image` metadata, which is first-party code describing itself.
