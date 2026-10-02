/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A catalog read by a classpath that lacks a provider it was written for. An entry whose
 * provider is absent is linked in memory to a present provider of its addressing (the record's
 * word, else its platform), keeping
 * its id and wire id, its own {@code spec_type} dropped so the new provider's defaults shape it;
 * an entry whose platform has no present provider is left out of the loaded catalog with the
 * pins naming it, while the raw view keeps both as written; an entry with no platform on record
 * loads as written when this classpath can build its spec type, and is left out the same way when
 * it cannot, while a present provider's entry naming an undeclared spec type is refused. Only the
 * runtime's own load warns about what it linked and left out; a view over the same file does not.
 * The fakes on this classpath are {@code fake-llm}, {@code fake-embeddings}
 * and {@code fake-decision}, all on the platform {@code fake}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
class CatalogLinkingTest {
    private static String llm(String id, String providerKey, String extra) {
        return "{ \"id\": \"" + id + "\", \"identity\": \"" + id + "\", \"grade\": \"SMALL\", \"provider_key\": \"" + providerKey + "\","
                + " \"wire_model_id\": \"wire-" + id + "\", \"max_context_tokens\": 1000, \"max_output_tokens\": 100, \"tpm\": 5000" + extra + " }";
    }

    private static JsonModelsBackend catalog(String json) {
        return new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("test", json, false)));
    }

    @Test
    void anEntryOfAnAbsentProviderLinksToAProviderOfItsPlatform() {
        JsonModelsBackend catalog = catalog("{ \"models\": [" + llm("moved", "gone-llm", ", \"platform\": \"fake\"") + ","
                + "{ \"id\": \"vectors\", \"identity\": \"vectors\", \"provider_key\": \"gone-embeddings\", \"platform\": \"fake\","
                + " \"wire_model_id\": \"wire-vectors\", \"max_context_tokens\": 1000, \"max_output_tokens\": 1, \"tpm\": 5000 } ] }");
        ModelSpec moved = catalog.spec("moved");
        assertEquals("fake-llm", moved.getProviderKey(), "linked to the present LLM provider of its platform");
        assertEquals("wire-moved", moved.getWireModelId(), "only the provider changes");
        assertEquals("fake-embeddings", catalog.spec("vectors").getProviderKey(), "an embeddings entry links to an embeddings provider");
        assertEquals("gone-llm", catalog.rawEntries().get("moved").get("provider_key").asText(), "the raw view keeps the entry as written");
    }

    @Test
    void thePlatformComesFromTheRecordWhenTheEntryStatesNone() {
        JsonModelsBackend catalog = catalog("{ \"providers\": { \"gone-llm\": { \"platform\": \"fake\" } }, \"models\": ["
                + llm("moved", "gone-llm", "") + "] }");
        assertEquals("fake-llm", catalog.spec("moved").getProviderKey());
        assertEquals("fake", catalog.rawProviders().get("gone-llm").get("platform").asText(), "the record is kept as written");
    }

    @Test
    void anEntrySpelledForAnEndpointNoProviderHereReachesIsUnavailable() {
        JsonModelsBackend catalog = catalog("{ \"providers\": { \"gone-own\": { \"platform\": \"fake\", \"addressing\": \"fake-own\" },"
                + " \"gone-llm\": { \"platform\": \"fake\", \"addressing\": \"fake\" } }, \"models\": ["
                + llm("own-endpoint", "gone-own", "") + "," + llm("moved", "gone-llm", "") + "] }");
        assertNull(catalog.spec("own-endpoint"), "the platform has a provider here, but none spells ids for that endpoint");
        assertEquals("fake-llm", catalog.spec("moved").getProviderKey(), "the same platform, the same addressing: linked");
    }

    @Test
    void aLinkedEntryDropsItsOwnSpecTypeForTheNewProvidersDefaults() {
        JsonModelsBackend catalog = catalog("{ \"provider_defaults\": { \"gone-llm\": { \"spec_type\": \"no-artifact-here\" } }, \"models\": ["
                + llm("moved", "gone-llm", ", \"platform\": \"fake\", \"spec_type\": \"no-artifact-here\"") + "] }");
        assertEquals(StandardModelSpec.class, catalog.spec("moved").getClass(),
                "neither the entry's spec type nor its old provider's defaults apply once it is linked to fake-llm");
    }

    @Test
    void anEntryWhosePlatformHasNoProviderHereIsLeftOutWithItsPins() {
        JsonModelsBackend catalog = catalog("{ \"models\": [" + llm("kept", "fake-llm", "") + ","
                + llm("stranded", "gone-llm", ", \"platform\": \"elsewhere\", \"spec_type\": \"no-artifact-here\"")
                + "], \"pins\": { \"SMALL\": [\"stranded\", \"kept\"] } }");
        assertNull(catalog.spec("stranded"), "no provider of its platform here: not in the loaded catalog");
        assertEquals(List.of("kept"), catalog.all().stream().map(ModelSpec::getId).toList());
        assertTrue(catalog.rawEntries().containsKey("stranded"), "the raw view keeps it, for the discovery to carry through");
        assertEquals(List.of("kept"), catalog.pins().grades().get(Grade.SMALL), "its place in the order is dropped, and the rest of the order stands");
        assertEquals("stranded", catalog.rawPins().get("SMALL").get(0).asText(), "the raw pins keep it as written");
    }

    @Test
    void anEntryWithNoPlatformOnRecordLoadsAsWritten() {
        JsonModelsBackend catalog = catalog("{ \"models\": [" + llm("unlinked", "gone-llm", "") + "] }");
        assertEquals("gone-llm", catalog.spec("unlinked").getProviderKey(), "nothing says where it came from, so nothing links it");
    }

    @Test
    void anEntryWithNoPlatformOnRecordWhoseSpecTypeNoArtifactHereDeclaresIsLeftOut() {
        JsonModelsBackend catalog = catalog("{ \"provider_defaults\": { \"gone-llm\": { \"spec_type\": \"no-artifact-here\" } }, \"models\": ["
                + llm("kept", "fake-llm", "") + "," + llm("unbuildable", "gone-llm", "") + "], \"pins\": { \"SMALL\": [\"unbuildable\"] } }");
        assertNull(catalog.spec("unbuildable"), "its provider is absent and its shape is declared by nothing here: nothing here can serve it");
        assertTrue(catalog.rawEntries().containsKey("unbuildable"), "the raw view keeps it as written");
        assertNull(catalog.pins().grades().get(Grade.SMALL), "an order left empty is dropped, and the grade falls to its cheapest entries");
    }

    /** A runtime catalog over {@code /models-linking.json}: one entry to link, one nothing here serves, pinned. */
    static class RuntimeCatalog extends JsonModelsBackend {
        RuntimeCatalog() {
            super("/models-linking.json");
        }
    }

    @Test
    void theRuntimesLoadWarnsOnceAndAViewOverTheSameFileStaysQuiet() throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(JsonModelsBackend.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new RuntimeCatalog();
            List<String> warnings = appender.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertEquals(2, warnings.size(), "one for what was linked and left out, one for the dropped pin: " + warnings);
            assertTrue(warnings.get(0).contains("moved (gone-llm -> fake-llm)") && warnings.get(0).contains("stranded"), warnings.get(0));
            assertTrue(warnings.get(1).contains("MEDIUM -> stranded"), warnings.get(1));
            appender.list.clear();
            String json = new String(CatalogLinkingTest.class.getResourceAsStream("/models-linking.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            catalog(json);
            assertTrue(appender.list.stream().noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN),
                    "a view over the same file links the same way and says nothing");
        }
        finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aPresentProvidersEntryWithASpecTypeNoArtifactDeclaresIsStillRefused() {
        UncorrectableRuntimeLLMException refusal = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> catalog("{ \"models\": [" + llm("misshapen", "fake-llm", ", \"spec_type\": \"no-artifact-here\"") + "] }"));
        assertTrue(refusal.getMessage().contains("no-artifact-here"), refusal.getMessage());
    }
}
