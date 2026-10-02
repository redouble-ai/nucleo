/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the catalog deprecation contract without naming models: nothing the suite's deployment
 * picker serves is retired, and whatever IS retired stays resolvable and priced (the
 * recorded-model reverse-lookup must keep pricing historical rows). Which identities are
 * retired is catalog data - models come and go; the invariants do not.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-16)
 */
class DeprecatedModelTest {

    private final ModelsBackend backend = new JsonModelsBackend();

    private List<ModelSpec> deprecated() {
        return backend.all().stream().filter(spec -> spec.getStatus() == ModelStatus.DEPRECATED).toList();
    }

    @Test
    void pickerNeverServesARetiredSpec() {
        TestModelPicker picker = new TestModelPicker();
        Situation situation = new Situation();
        situation.setEnvelope(spec -> true);
        for (Grade grade : Grade.rungs()) {
            ModelSpec spec = picker.provide(new Seat(DeprecatedModelTest.class, grade, ModelKind.LLM), situation);
            assertEquals(ModelStatus.OPEN, spec.getStatus(), grade + " resolved to retired spec " + spec.getId());
        }
        assertEquals(ModelStatus.OPEN, picker.embeddingsSpec().getStatus(), picker.embeddingsSpec().getId());
    }

    @Test
    void deprecatedSpecsStayResolvableForBilling() {
        // Deprecation must not remove the entry: recorded calls hold these ids and are priced by
        // reverse-lookup. If resolution ever returns null, historical cost reporting silently breaks.
        for (ModelSpec spec : deprecated()) {
            assertNotNull(backend.spec(spec.getId()), spec.getId() + " must remain resolvable after deprecation");
            // Embeddings entries carry no per-million prices - the billing invariant is LLM-only
            if (!spec.isEmbeddings()) {
                assertTrue(spec.isPriced(), spec.getId() + " must keep its price for historical rows");
            }
        }
    }

    @Test
    void deprecatedSpecsKeepTheirWireIdAlias() {
        // The billing path looks specs up by the provider's wire id, not the catalog id. A twin on
        // another endpoint may share a wire id, so the pin is on the wire vocabulary resolving at
        // all, not on which twin answers.
        for (ModelSpec spec : deprecated()) {
            ModelSpec byWire = backend.spec(spec.getWireModelId());
            assertNotNull(byWire, spec.getId() + ": wire id " + spec.getWireModelId() + " no longer resolves");
            assertEquals(spec.getWireModelId(), byWire.getWireModelId(), spec.getId());
        }
    }
}
