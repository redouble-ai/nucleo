/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the demo says about its catalog: nothing to fix when the file is there and ordered; where
 * the runtime looked, the file the demo keeps it in and how to write it when it is not there, or
 * that a process outside its module has no such file; the ordering step when it is there
 * without pins.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
class DemoCatalogTest {
    private static final Path DIRECTORY = Path.of("/work/redouble-nucleo/nucleo-demo");
    private static final Path EXPECTED = DIRECTORY.resolve("src/main/resources/models.json");
    /** The host's own wording, handed in at construction: the module a person opens and the command they run there. */
    private static final String MODULE = "nucleo-demo";
    private static final String DISCOVER_COMMAND = "java -jar target/nucleo-demo.jar discover 2>&1 | tee discovery.txt";
    private static final DemoCatalog CATALOG = new DemoCatalog(MODULE, DISCOVER_COMMAND);

    @Test
    void aMissingCatalogSaysWhereTheRuntimeLookedWhereTheDemoKeepsItAndHowToWriteIt() {
        DemoCatalog.Status status = CATALOG.status(DIRECTORY, EXPECTED, null);
        assertFalse(status.found());
        assertNull(status.source());
        assertEquals(EXPECTED.toString(), status.catalogFile());
        String all = String.join("\n", status.instructions());
        assertTrue(all.contains("no models.json on this process's classpath"), all);
        assertTrue(all.contains("-Dnucleo.models names none"), all);
        assertTrue(all.contains("keeps its catalog in " + EXPECTED), all);
        assertTrue(all.contains("press Discover models"), all);
        assertTrue(all.contains("edit the models table"), "an edit also creates the file: " + all);
        assertTrue(all.contains("AGENTS.md steps 3 to 5"), all);
        assertTrue(all.contains(DISCOVER_COMMAND), all);
    }

    @Test
    void outsideItsModuleTheDemoSaysItHasNoFileToKeepACatalogIn() {
        DemoCatalog.Status status = CATALOG.status(DIRECTORY, null, null);
        assertNull(status.catalogFile());
        String all = String.join("\n", status.instructions());
        assertTrue(all.contains("outside the demo's module, " + MODULE), all);
        assertTrue(all.contains(MODULE + "/src/main/resources/models.json"), all);
        assertTrue(all.contains("-Dnucleo.models=<path>"), all);
    }

    @Test
    void anOrderedCatalogNeedsNothing() {
        DemoCatalog.Status status = CATALOG.status(DIRECTORY, EXPECTED, new Catalog("/models.json"));
        assertTrue(status.found());
        assertTrue(status.instructions().isEmpty(), String.valueOf(status.instructions()));
        assertEquals(List.of("claude-haiku-4-5-bedrock"), status.orders().get("SMALL"));
        assertEquals("cohere-embed-v4-bedrock", status.pins().get("embeddings"));
        assertFalse(status.pins().containsKey("SMALL"), "a grade is ordered, never a pin");
        assertTrue(status.entries() > 0);
    }

    @Test
    void aCatalogWithoutPinsIsPointedAtTheOrderingStep() {
        DemoCatalog.Status status = CATALOG.status(DIRECTORY, EXPECTED, new Catalog("/test-models-unpinned.json"));
        assertTrue(status.found());
        assertTrue(status.orders().isEmpty());
        assertTrue(status.pins().isEmpty());
        assertEquals(1, status.instructions().size());
        assertEquals("Order the models of each grade in the models table: the first one a grade can call serves it.", status.instructions().get(0),
                "one line saying what to do, where the page does it");
    }

    /**
     * The banner names the catalog's source in words: a file URL as its path, a jar-borne
     * catalog as "packaged inside the jar" with the jar's path kept - never Boot's
     * jar:nested:...!/ spelling - and anything unrecognized verbatim.
     */
    @Test
    void theSourceReadsAsWordsNotAsAClassLoaderUrl() {
        assertEquals("/work/nucleo-demo/target/classes/models.json",
                DemoCatalog.describeSource("file:/work/nucleo-demo/target/classes/models.json"));
        assertEquals("models.json packaged inside nucleo-demo-0.1.0.jar at build time"
                        + " (/work/nucleo-demo/target/nucleo-demo-0.1.0.jar)",
                DemoCatalog.describeSource("jar:nested:/work/nucleo-demo/target/nucleo-demo-0.1.0.jar/!BOOT-INF/classes/!/models.json"));
        assertEquals("models.json packaged inside app.jar at build time (/opt/app.jar)",
                DemoCatalog.describeSource("jar:file:/opt/app.jar!/models.json"));
        assertEquals("/work/nucleo-demo/models.json", DemoCatalog.describeSource("/work/nucleo-demo/models.json"),
                "a plain path, as the filesystem layers name themselves, passes through");
    }

    /**
     * The page's edits land in the deployment's own file, validated by loading before they are
     * written and adopted by the runtime live: a grade moves an entry to another rung and out of
     * the order of the grade it left, an order lists a grade's entries in the deployment's
     * preference, a pin names the embeddings or decision entry, and the status a person sets is
     * OPEN or DISABLED. A pinned entry is not disabled under its pin, a placed one is and keeps
     * its place, an embeddings entry gets no grade, a closed entry is not pinned, and a grade is
     * no pin. A price is a non-negative number in the entry's currency, an output price only on
     * an LLM entry, and an entry the discovery inferred stops being unverified when a person
     * edits its grade or a price or confirms it. Run on a temporary copy of the test catalog,
     * named through the property the runtime reads its catalog from.
     */
    @Test
    void thePagesEditsAreWrittenValidatedAndAdoptedLive(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("models.json");
        Files.writeString(file, new String(getClass().getResourceAsStream("/models.json").readAllBytes(), StandardCharsets.UTF_8));
        String property = System.getProperty(JsonModelsBackend.PROPERTY);
        System.setProperty(JsonModelsBackend.PROPERTY, file.toString());
        try {
            Models.reload();
            assertEquals(file.toString(), CATALOG.status().editableFile(), "a file of the deployment's own is what the page edits");

            // prices: written in the entry's currency, adopted live, and a person's price confirms the discovery's inference
            assertTrue(Models.spec("nova-micro").unverified(), "the fixture's nova-micro is the discovery's inference");
            CATALOG.editEntry("nova-micro", null, null, 0.04, null, null);
            assertEquals(0.04, Models.spec("nova-micro").getInputPricePerMillion(), "the runtime adopted the price live");
            assertEquals(0.14, Models.spec("nova-micro").getOutputPricePerMillion(), "the other price is untouched");
            assertFalse(Models.spec("nova-micro").unverified(), "a person's price replaces the inference");
            CATALOG.editEntry("nova-micro", null, null, null, 0.16, null);
            assertEquals(0.16, Models.spec("nova-micro").getOutputPricePerMillion());
            assertTrue(assertThrows(IllegalArgumentException.class, () -> CATALOG.editEntry("nova-micro", null, null, -1.0, null, null))
                    .getMessage().contains("non-negative"), "a negative price is refused");
            assertTrue(assertThrows(IllegalArgumentException.class, () -> CATALOG.editEntry("cohere-embed-v4-bedrock", null, null, null, 0.5, null))
                    .getMessage().contains("no output price"), "an embeddings call has no output the provider bills");
            CATALOG.editEntry("cohere-embed-v4-bedrock", null, null, 0.1, null, null);
            assertEquals(0.1, Models.spec("cohere-embed-v4-bedrock").getInputPricePerMillion(), "an embeddings entry has an input price");
            String inferred = Files.readString(file).replace("\"id\" : \"nova-micro\",", "\"id\" : \"nova-micro\", \"unverified\" : true,");
            Files.writeString(file, inferred);
            Models.reload();
            assertTrue(Models.spec("nova-micro").unverified());
            CATALOG.editEntry("nova-micro", null, null, null, null, true);
            assertFalse(Models.spec("nova-micro").unverified(), "confirmed as it is: a person checked the inference");
            assertFalse(Files.readString(file).contains("\"unverified\""), "the mark is removed from the file, never written false");

            DemoCatalog.Status status = edit("claude-haiku-4-5-bedrock", "MEDIUM", null);
            assertEquals(Grade.MEDIUM, Models.spec("claude-haiku-4-5-bedrock").getGrade(), "the runtime adopted the new grade live");
            assertTrue(Files.readString(file).contains("\"grade\" : \"MEDIUM\""), "and the file carries it");
            assertNull(status.orders().get("SMALL"), "it left the order of the grade it left, which it alone was in");
            assertEquals(List.of("claude-sonnet-5-bedrock"), status.orders().get("MEDIUM"), "and starts unplaced in its new grade");
            status = edit("claude-haiku-4-5-bedrock", "SMALL", null);

            // an order is the grade's entries in the deployment's preference, an entry above the grade included
            status = CATALOG.order("SMALL", List.of("claude-sonnet-5-bedrock", "claude-haiku-4-5-bedrock"));
            assertEquals(List.of("claude-sonnet-5-bedrock", "claude-haiku-4-5-bedrock"), status.orders().get("SMALL"));
            assertEquals(List.of("claude-sonnet-5-bedrock", "claude-haiku-4-5-bedrock"), Models.pins().grades().get(Grade.SMALL), "adopted live");
            String before = Files.readString(file);
            IllegalArgumentException below = assertThrows(IllegalArgumentException.class,
                    () -> CATALOG.order("LARGE", List.of("claude-haiku-4-5-bedrock")));
            assertTrue(below.getMessage().contains("never below"), "the loader's own rule: " + below.getMessage());
            IllegalArgumentException twice = assertThrows(IllegalArgumentException.class,
                    () -> CATALOG.order("SMALL", List.of("claude-haiku-4-5-bedrock", "claude-haiku-4-5-bedrock")));
            assertTrue(twice.getMessage().contains("twice"), "the loader's own rule: " + twice.getMessage());
            IllegalArgumentException notARung = assertThrows(IllegalArgumentException.class, () -> CATALOG.order("CEILING", List.of("claude-sonnet-5-bedrock")));
            assertTrue(notARung.getMessage().contains("not a rung"), notARung.getMessage());
            assertEquals(before, Files.readString(file), "a refused order wrote nothing");

            // a placed entry can be disabled: it keeps its place and the runtime passes over it
            status = edit("claude-sonnet-5-bedrock", null, "DISABLED");
            assertEquals(ModelStatus.DISABLED, Models.spec("claude-sonnet-5-bedrock").getStatus());
            assertEquals(List.of("claude-sonnet-5-bedrock", "claude-haiku-4-5-bedrock"), status.orders().get("SMALL"), "its place is kept");
            status = edit("claude-sonnet-5-bedrock", null, "OPEN");
            assertEquals(ModelStatus.OPEN, Models.spec("claude-sonnet-5-bedrock").getStatus());
            assertFalse(Files.readString(file).contains("\"status\""), "OPEN is the default, so the field is removed rather than written");
            status = CATALOG.order("SMALL", List.of());
            assertNull(status.orders().get("SMALL"), "an empty order clears the grade's");
            assertFalse(Models.pins().grades().containsKey(Grade.SMALL));

            // a grade is no pin, and neither is the strongest rung, which the picker derives
            IllegalArgumentException gradePin = assertThrows(IllegalArgumentException.class, () -> CATALOG.pin("SMALL", "claude-haiku-4-5-bedrock"));
            assertTrue(gradePin.getMessage().contains("'SMALL' is not a pin"), gradePin.getMessage());
            IllegalArgumentException ceiling = assertThrows(IllegalArgumentException.class, () -> CATALOG.pin("ceiling", "claude-sonnet-5-bedrock"));
            assertTrue(ceiling.getMessage().contains("'ceiling' is not a pin"), ceiling.getMessage());

            // the embeddings entry is pinned, and a pinned entry is not disabled under its pin
            IllegalArgumentException pinned = assertThrows(IllegalArgumentException.class,
                    () -> edit("cohere-embed-v4-bedrock", null, "DISABLED"));
            assertTrue(pinned.getMessage().contains("the default for embeddings"), pinned.getMessage());

            IllegalArgumentException vendors = assertThrows(IllegalArgumentException.class,
                    () -> edit("nova-micro", null, "DEPRECATED"));
            assertTrue(vendors.getMessage().contains("vendor's"), vendors.getMessage());
            IllegalArgumentException noRung = assertThrows(IllegalArgumentException.class,
                    () -> edit("cohere-embed-v4-bedrock", "SMALL", null));
            assertTrue(noRung.getMessage().contains("carries no grade"), noRung.getMessage());
            IllegalArgumentException wrongKind = assertThrows(IllegalArgumentException.class,
                    () -> CATALOG.pin("embeddings", "nova-micro"));
            assertTrue(wrongKind.getMessage().contains("not an embeddings entry"), "the loader's own rule: " + wrongKind.getMessage());
            IllegalArgumentException notADecision = assertThrows(IllegalArgumentException.class,
                    () -> CATALOG.pin("decision", "nova-micro"));
            assertTrue(notADecision.getMessage().contains("not a decision entry"), "the loader's own rule: " + notADecision.getMessage());
            assertNull(CATALOG.status().pins().get("decision"), "no decision pin yet");
            status = CATALOG.pin("decision", "kev");
            assertEquals("kev", status.pins().get("decision"), "the decision pin is a slot like embeddings");
            assertEquals("kev", Models.pins().decision(), "and the runtime adopted it live");
            IllegalArgumentException pinnedDecision = assertThrows(IllegalArgumentException.class,
                    () -> edit("kev", null, "DISABLED"));
            assertTrue(pinnedDecision.getMessage().contains("the default for decision"),
                    "the pinned decision entry is not disabled under its pin either: " + pinnedDecision.getMessage());
            IllegalArgumentException noRungEither = assertThrows(IllegalArgumentException.class,
                    () -> edit("kev", "SMALL", null));
            assertTrue(noRungEither.getMessage().contains("a decision entry"), noRungEither.getMessage());
            status = CATALOG.pin("decision", null);
            assertNull(status.pins().get("decision"));
            status = edit("kev", null, "DISABLED");
            IllegalArgumentException closed = assertThrows(IllegalArgumentException.class, () -> CATALOG.pin("decision", "kev"));
            assertTrue(closed.getMessage().contains("only an open entry can be pinned"), closed.getMessage());
            status = edit("kev", null, "OPEN");
            before = Files.readString(file);
            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> edit("no-such", "SMALL", null));
            assertTrue(unknown.getMessage().contains("No entry 'no-such'"), unknown.getMessage());
            assertEquals(before, Files.readString(file), "a refused edit wrote nothing");
        }
        finally {
            if (property != null) {
                System.setProperty(JsonModelsBackend.PROPERTY, property);
            }
            else {
                System.clearProperty(JsonModelsBackend.PROPERTY);
            }
            Models.reload();
        }
    }

    /** Outside the demo's module an edit has nowhere to land or to create: the build's copy is never edited. */
    @Test
    void anEditOutsideTheModuleIsRefusedOnTheBuildsCopy() {
        assertNull(DemoCatalog.editableFile(null));
        assertNull(DemoCatalog.editableFile(new Catalog("/models.json")), "a classpath resource is the build's copy, never edited");
        String property = System.getProperty(JsonModelsBackend.PROPERTY);
        System.clearProperty(JsonModelsBackend.PROPERTY);
        try {
            IllegalStateException nowhere = assertThrows(IllegalStateException.class, () -> CATALOG.order("XL", List.of("claude-opus-5-bedrock")));
            assertTrue(nowhere.getMessage().contains("build's copy"), nowhere.getMessage());
            assertTrue(nowhere.getMessage().contains("Discover models"), nowhere.getMessage());
        }
        finally {
            if (property != null) {
                System.setProperty(JsonModelsBackend.PROPERTY, property);
            }
        }
    }

    /**
     * The deployment's file appears only from a person's action: with no file yet and a module to
     * keep one in, the first edit materializes the shipped defaults, lands in them, and the
     * runtime adopts the file live.
     */
    @Test
    void theFirstEditMaterializesTheShippedDefaultsAndLandsInThem(@TempDir Path dir) throws Exception {
        String property = System.getProperty(JsonModelsBackend.PROPERTY);
        System.clearProperty(JsonModelsBackend.PROPERTY);
        Path home = dir.resolve("src/main/resources/models.json");
        DemoHome.homeForTests(home);
        try {
            assertFalse(Files.exists(home), "no action was taken yet, so no file exists");
            DemoCatalog.Status status = CATALOG.order("SMALL", List.of());
            assertTrue(Files.isRegularFile(home), "the first edit is the action that creates the file");
            assertEquals(home.toString(), status.editableFile(), "and the edit landed in it");
            assertEquals(home.toString(), System.getProperty(JsonModelsBackend.PROPERTY), "the runtime adopted the file it will keep");
            assertTrue(status.entries() > 0, "started from the shipped defaults, never from nothing");
        }
        finally {
            DemoHome.homeForTests(null);
            if (property != null) {
                System.setProperty(JsonModelsBackend.PROPERTY, property);
            }
            else {
                System.clearProperty(JsonModelsBackend.PROPERTY);
            }
            Models.reload();
        }
    }

    /** The grade and status edit alone, as the page sends it; the prices and the confirmation stay as they are. */
    private static DemoCatalog.Status edit(String id, String grade, String status) {
        return CATALOG.editEntry(id, grade, status, null, null, null);
    }

    /** A catalog loaded from a test resource, as a discovery-written file would load. */
    static final class Catalog extends JsonModelsBackend {
        Catalog(String resource) {
            super(resource);
        }
    }
}
