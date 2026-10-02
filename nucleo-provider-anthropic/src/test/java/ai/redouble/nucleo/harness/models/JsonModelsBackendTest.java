/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.providers.anthropic.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link JsonModelsBackend}: provider-defaults merge, wire-id alias resolution,
 * unknown-id null, fail-fast validation when a catalog field is misspelled (so a limit
 * silently defaults to zero), the discovery rule (the deployment's own file is the whole
 * catalog; the shipped fragments load only without one) and the {@code pins} object. The
 * test classpath carries no {@code /models.json}, so the no-arg backend loads the shipped
 * fragment for the happy paths.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
class JsonModelsBackendTest {

    private final ModelsBackend backend = new JsonModelsBackend();

    private static String entry(String id, String grade) {
        return "{ \"id\": \"" + id + "\", \"identity\": \"" + id + "\", \"grade\": \"" + grade + "\", \"provider_key\": \"openai\","
                + " \"wire_model_id\": \"" + id + "\", \"max_context_tokens\": 1000, \"max_output_tokens\": 100,"
                + " \"supports_vision\": false, \"thinking_mode\": \"NONE\", \"tpm\": 5000, \"rpm\": 100 }";
    }

    private static String catalog(String models, String pins) {
        return "{ \"models\": [" + models + "]" + (pins != null ? ", \"pins\": " + pins : "") + " }";
    }

    @Test
    void fragmentsLoadTogetherAndAFragmentMayNotPin() {
        // Without a deployment file every provider artifact's fragment is loaded, side by side.
        ModelsBackend fragments = new JsonModelsBackend(List.of(
                new JsonModelsBackend.Layer("a.jar", catalog(entry("from-a", "SMALL"), null), true),
                new JsonModelsBackend.Layer("b.jar", catalog(entry("from-b", "MEDIUM"), null), true)));
        assertNotNull(fragments.spec("from-a"));
        assertNotNull(fragments.spec("from-b"));
        assertNull(fragments.pins());
        // Pins are the deployment's decision; a jar cannot make it.
        UncorrectableRuntimeLLMException ex = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("a.jar",
                        catalog(entry("from-a", "SMALL"), "{ \"SMALL\": [\"from-a\"] }"), true))));
        assertTrue(ex.getMessage().contains("pins"));
    }

    @Test
    void duplicateIdAcrossFragmentsFailsFast() {
        // No fragment is "later" than another, so the same id twice has no resolution.
        UncorrectableRuntimeLLMException ex = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> new JsonModelsBackend(List.of(
                        new JsonModelsBackend.Layer("a.jar", catalog(entry("twice", "SMALL"), null), true),
                        new JsonModelsBackend.Layer("b.jar", catalog(entry("twice", "SMALL"), null), true))));
        assertTrue(ex.getMessage().contains("twice"));
        assertTrue(ex.getMessage().contains("a.jar"));
        assertTrue(ex.getMessage().contains("b.jar"));
    }

    @Test
    void pinsLoadAndNameCatalogEntries() {
        ModelsBackend pinned = new JsonModelsBackend("/models-pins.json");
        CatalogPins pins = pinned.pins();
        assertNotNull(pins);
        assertEquals(List.of("pinned-small"), pins.grades().get(Grade.SMALL));
        assertFalse(pins.grades().containsKey(Grade.MEDIUM), "an undeclared grade is left to the picker");
        assertEquals("embed-b", pins.embeddings());
    }

    /**
     * A grade's value is its order: an array of catalog ids, the first the grade's default, each
     * an LLM entry of the grade or above, none twice; an empty array declares none. A single id
     * is refused with the reason, so a catalog written before orders fails loudly.
     */
    @Test
    void aGradesOrderIsAnArrayOfEntriesOfTheGradeOrAbove() {
        String models = entry("small", "SMALL") + "," + entry("other", "SMALL") + "," + entry("large", "LARGE");
        assertEquals(List.of("large", "small", "other"), new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("mine",
                catalog(models, "{ \"SMALL\": [\"large\", \"small\", \"other\"] }"), false))).pins().grades().get(Grade.SMALL),
                "the deployment's order, kept, an entry above the grade included");
        assertFalse(new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("mine", catalog(models, "{ \"SMALL\": [] }"), false)))
                .pins().grades().containsKey(Grade.SMALL), "an empty array declares no order");
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class, () -> new JsonModelsBackend(List.of(
                new JsonModelsBackend.Layer("mine", catalog(models, "{ \"SMALL\": \"small\" }"), false))))
                .getMessage().contains("must be an array"), "a single id is refused");
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class, () -> new JsonModelsBackend(List.of(
                new JsonModelsBackend.Layer("mine", catalog(models, "{ \"SMALL\": [\"small\", \"small\"] }"), false))))
                .getMessage().contains("twice"), "an entry placed twice is refused");
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class, () -> new JsonModelsBackend(List.of(
                new JsonModelsBackend.Layer("mine", catalog(models, "{ \"LARGE\": [\"small\"] }"), false))))
                .getMessage().contains("never below"), "an entry below the grade is refused");
    }

    @Test
    void pinsAreValidatedAgainstTheCatalog() {
        String models = entry("small", "SMALL");
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class, () -> new JsonModelsBackend(List.of(
                new JsonModelsBackend.Layer("mine", catalog(models, "{ \"SMALL\": [\"no-such-entry\"] }"), false))))
                .getMessage().contains("no-such-entry"));
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class, () -> new JsonModelsBackend(List.of(
                new JsonModelsBackend.Layer("mine", catalog(models, "{ \"HUGE\": [\"small\"] }"), false))))
                .getMessage().contains("HUGE"));
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class, () -> new JsonModelsBackend(List.of(
                new JsonModelsBackend.Layer("mine", catalog(models, "{ \"CEILING\": [\"small\"] }"), false))))
                .getMessage().contains("CEILING"));
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class, () -> new JsonModelsBackend(List.of(
                new JsonModelsBackend.Layer("mine", catalog(models, "{ \"ceiling\": \"XL\" }"), false))))
                .getMessage().contains("'ceiling' names no grade"), "the strongest grade is derived by the picker, never pinned");
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class, () -> new JsonModelsBackend(List.of(
                new JsonModelsBackend.Layer("mine", catalog(models, "{ \"embeddings\": \"small\" }"), false))))
                .getMessage().contains("not an embeddings entry"));
        String withEmbeddings = models + ", { \"id\": \"embed-x\", \"identity\": \"embed-x\","
                + " \"provider_key\": \"openai-embeddings\", \"wire_model_id\": \"embed-x\","
                + " \"max_context_tokens\": 1000, \"max_output_tokens\": 1000, \"supports_vision\": false,"
                + " \"thinking_mode\": \"NONE\", \"tpm\": 5000, \"rpm\": 100 }";
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class, () -> new JsonModelsBackend(List.of(
                new JsonModelsBackend.Layer("mine", catalog(withEmbeddings, "{ \"SMALL\": [\"embed-x\"] }"), false))))
                .getMessage().contains("graded LLM"), "a grade's order names graded LLM entries, never an embeddings entry");
    }

    @Test
    void anEntryDeclaringGradeCeilingIsRefusedAtLoad() {
        assertTrue(assertThrows(UncorrectableRuntimeLLMException.class, () -> new JsonModelsBackend(List.of(
                new JsonModelsBackend.Layer("mine", catalog(entry("too-strong", "CEILING"), null), false))))
                .getMessage().contains("never a rung an entry can carry"),
                "CEILING is a seat's word for the strongest rung, not a grade an entry may state");
    }

    @Test
    void providerDefaultsMergeIntoEveryFamilyMember() {
        // The Anthropic family constants (0.10/1.25 cache weights, 4 breakpoints, the typed spec
        // subclass) live once in provider_defaults; every entry of every anthropic-* provider must
        // inherit them - a per-family fact, indifferent to which models the families carry.
        boolean anthropicSeen = false;
        for (ModelSpec spec : backend.all()) {
            if (spec.getProviderKey().startsWith("anthropic-")) {
                anthropicSeen = true;
                assertEquals(0.10, spec.getCacheReadMultiplier(), 0.0001, spec.getId());
                assertEquals(1.25, spec.getCacheWriteMultiplier(), 0.0001, spec.getId());
                assertInstanceOf(AnthropicModelSpec.class, spec, spec.getId());
                assertEquals(4, ((AnthropicModelSpec) spec).getCacheBreakpoints(), spec.getId());
            }
        }
        assertTrue(anthropicSeen, "the shipped catalog carries no anthropic-* entries at all");
    }

    @Test
    void resolvesByWireIdAlias() {
        // The billing path holds the provider's wire id, not the catalog id: every wire id in the
        // catalog must resolve. Twins on another endpoint may share a wire id, so the guarantee is
        // on the wire vocabulary, not on which twin answers.
        for (ModelSpec spec : backend.all()) {
            ModelSpec byWire = backend.spec(spec.getWireModelId());
            assertNotNull(byWire, spec.getId() + ": wire id " + spec.getWireModelId() + " does not resolve");
            assertEquals(spec.getWireModelId(), byWire.getWireModelId(), spec.getId());
        }
    }

    @Test
    void unknownIdReturnsNull() {
        assertNull(backend.spec("no-such-model"));
    }

    @Test
    void loadsOptionalPricesInTheirCurrencyAndNullsWhenAbsent() {
        ModelsBackend priced = new JsonModelsBackend("/models-priced.json");
        ModelSpec withPrice = priced.spec("priced");
        assertNotNull(withPrice);
        assertEquals("USD", withPrice.getCurrency(), "the provider defaults' currency");
        assertTrue(withPrice.isPriced());
        assertEquals(1.25, withPrice.getInputPricePerMillion(), 0.0001);
        assertEquals(10.0, withPrice.getOutputPricePerMillion(), 0.0001);
        // Explicit cache-read price loads; cache-write is absent and stays null (derived at use).
        assertEquals(0.125, withPrice.getCacheReadPricePerMillion(), 0.0001);
        assertNull(withPrice.getCacheWritePricePerMillion());
        // Prices are optional: an entry that omits them loads fine with null rates (not 0.0).
        ModelSpec noPrice = priced.spec("unpriced");
        assertNotNull(noPrice);
        assertFalse(noPrice.isPriced());
        assertNull(noPrice.getInputPricePerMillion());
        assertNull(noPrice.getOutputPricePerMillion());
        assertNull(noPrice.getCacheReadPricePerMillion());
    }

    @Test
    void aPriceWithoutACurrencyIsRefused() {
        String entry = "{ \"id\": \"x\", \"identity\": \"x\", \"grade\": \"SMALL\", \"provider_key\": \"openai\", \"wire_model_id\": \"x\","
                + " \"max_context_tokens\": 1000, \"max_output_tokens\": 100, \"supports_vision\": false, \"thinking_mode\": \"NONE\","
                + " \"tpm\": 5000, \"rpm\": 100, \"input_price_per_million\": 1.0, \"output_price_per_million\": 2.0 }";
        assertThrows(UncorrectableRuntimeLLMException.class, () -> new JsonModelsBackend(List.of(
                new JsonModelsBackend.Layer("test", "{ \"models\": [" + entry + "] }", false))),
                "a number with no currency is not a price");
        assertThrows(UncorrectableRuntimeLLMException.class, () -> new JsonModelsBackend(List.of(
                new JsonModelsBackend.Layer("test", "{ \"models\": [" + entry.replace("\"tpm\"", "\"currency\": \"dollars\", \"tpm\"") + "] }", false))),
                "a currency is an ISO 4217 code");
    }

    @Test
    void nonPositiveEmbeddingDimensionsFailsFast() {
        // The field is optional, but 0 or negative would surface later as a broken vector
        // allocation deep inside a job; the backend must reject it at load.
        UncorrectableRuntimeLLMException ex = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> new JsonModelsBackend("/models-bad-dims.json"));
        assertTrue(ex.getMessage().contains("embedding_dimensions"));
    }

    @Test
    void misspelledLimitKeyFailsFast() {
        // models-bad.json spells tpm as "tokes_per_minute", leaving tpm at 0. Without
        // validation that would silently mis-limit; the backend must throw at load.
        UncorrectableRuntimeLLMException ex = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> new JsonModelsBackend("/models-bad.json"));
        assertTrue(ex.getMessage().contains("tpm"));
    }
}
