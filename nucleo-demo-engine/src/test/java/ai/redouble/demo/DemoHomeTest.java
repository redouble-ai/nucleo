/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where the demo writes its catalog: its module's {@code src/main/resources/models.json}, found
 * from where the host's code runs by Maven's standard layout - the IDE's classes directory, a
 * Spring Boot jar, a Quarkus application, a native executable - and nowhere for code that sits in
 * no module. The file appears only from a person's action, never from a start: materializing
 * writes the providers' shipped fragments merged into one file, recording the providers it was
 * written for with their platforms and each entry's platform, as a discovered catalog does, so a
 * host whose classpath carries other providers links its entries rather than failing on them;
 * a file already there is the person's and is never touched.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
class DemoHomeTest {
    @Test
    void everyLaunchModesCodeLeadsToItsModulesResources(@TempDir Path dir) throws Exception {
        Path module = Files.createDirectories(dir.resolve("nucleo-demo"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Path expected = module.resolve("src/main/resources/models.json");
        assertEquals(expected, DemoHome.moduleCatalog(module.resolve("target/classes")), "the IDE's classes directory");
        assertEquals(expected, DemoHome.moduleCatalog(module.resolve("target/nucleo-demo.jar")), "a Spring Boot jar");
        assertEquals(expected, DemoHome.moduleCatalog(module.resolve("target/quarkus-app/app/nucleo-demo.jar")), "a Quarkus application");
        assertEquals(expected, DemoHome.moduleCatalog(module.resolve("target/nucleo-demo-runner")), "a native executable");
    }

    @Test
    void codeInNoModuleHasNowhereToWrite(@TempDir Path dir) throws Exception {
        assertNull(DemoHome.moduleCatalog(dir.resolve("opt/app/app.jar")), "a jar copied to a server");
        Path unbuilt = Files.createDirectories(dir.resolve("elsewhere/target"));
        assertNull(DemoHome.moduleCatalog(unbuilt.resolve("app.jar")), "a directory named target with no pom.xml beside it is no module");
        assertNull(DemoHome.moduleCatalog(null));
    }

    @Test
    void theResourceUrlFormsNameTheFileOrJarTheyPointAt() {
        assertEquals(Path.of("/work/nucleo-demo/target/classes/application.yaml"),
                DemoHome.pathOf("file:/work/nucleo-demo/target/classes/application.yaml"), "a classes directory: the IDE, Quarkus dev mode");
        assertEquals(Path.of("/work/nucleo-demo/target/nucleo-demo.jar"),
                DemoHome.pathOf("jar:nested:/work/nucleo-demo/target/nucleo-demo.jar/!BOOT-INF/classes/!/application.yaml"),
                "Spring Boot's nested jar");
        assertEquals(Path.of("/work/q/target/quarkus-app/app/q.jar"),
                DemoHome.pathOf("jar:file:/work/q/target/quarkus-app/app/q.jar!/application.properties"), "a Quarkus application's jar");
        assertNull(DemoHome.pathOf("quarkus:ai/redouble/demo/quarkus/App.class"), "a dev-mode class file, which names no file");
        assertNull(DemoHome.pathOf("resource:/application.properties"), "a native image's form, which names no file");
    }

    @Test
    void theHostsOwnResourceLeadsFirstThenItsClassFileThenTheExecutable() {
        // the engine's test resources and test classes are under nucleo-demo-engine/target: its own module
        List<Path> locations = DemoHome.codeLocations(DemoHomeTest.class, "models.json");
        assertTrue(locations.get(0).endsWith(Path.of("test-classes", "models.json")), "the resource, as its loader serves it: " + locations);
        assertTrue(locations.stream().anyMatch(location -> location.endsWith("DemoHomeTest.class")), "the class file next: " + locations);
        assertEquals(ProcessHandle.current().info().command().map(Path::of).orElseThrow(), locations.get(locations.size() - 1),
                "the executable last, which is where a native image's code is");
        Path found = DemoHome.moduleCatalog(locations.get(0));
        assertTrue(found.endsWith(Path.of("nucleo-demo-engine", "src", "main", "resources", "models.json")), String.valueOf(found));
    }

    @Test
    void materializingWritesTheShippedDefaultsOnceAndNeverTouchesAFileAlreadyThere(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("src/main/resources/models.json");
        DemoHome.materializeInto(target);
        assertTrue(Files.isRegularFile(target), "the shipped defaults become the file the edit lands in");
        JsonNode written = NucleoJsonSerializer.readTree(Files.readString(target));
        assertFalse(written.get("models").isEmpty(), "the fragments' entries");
        String person = "{ \"models\": [], \"note\": \"the person's own\" }";
        Files.writeString(target, person);
        DemoHome.materializeInto(target);
        assertEquals(person, Files.readString(target), "a file already there is the person's, whatever it holds");
    }

    @Test
    void theSeedRecordsEveryProviderOfTheClasspathAndEachEntrysPlatform() throws Exception {
        ObjectNode seed = DemoHome.shippedCatalog();
        JsonNode record = seed.get(ProviderLinks.PROVIDERS);
        assertEquals(ProviderLinks.recordedKeys(record), new TreeSet<>(ClientProviders.all().keySet()),
                "every provider on this classpath, whether or not a fragment entry names it");
        assertEquals("bedrock", record.get("bedrock-converse").get(ProviderLinks.PLATFORM).asText());
        assertFalse(seed.get("models").isEmpty(), "the fragments' entries");
        for (JsonNode entry : seed.get("models")) {
            String key = entry.get("provider_key").asText();
            assertEquals(ClientProviders.get(key).platform(), entry.get(ProviderLinks.PLATFORM).asText(),
                    entry.get("id").asText() + " states its provider's platform");
        }
    }
}
