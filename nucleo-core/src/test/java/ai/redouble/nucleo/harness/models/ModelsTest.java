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
 * Covers the {@link Models} facade against the shipped catalog: id/wire-id resolution,
 * miss semantics (throw vs null), identity grouping, the learned-limit override winning on
 * both the canonical-id and wire-id views, the CEILING non-rung contract, and every
 * configured tier resolving. The family-guarded client cast lives with
 * {@code ClientProviders} and is covered there.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
class ModelsTest {

    @BeforeEach
    void reset() {
        Models.resetAll();
    }

    private static ModelSpec pick(Grade grade) {
        Situation situation = new Situation();
        situation.setEnvelope(spec -> true);
        return new TestModelPicker().provide(new Seat(ModelsTest.class, grade, ModelKind.LLM), situation);
    }

    @Test
    void specThrowsOnMissFindSpecReturnsNull() {
        assertThrows(ModelNotFoundException.class, () -> Models.spec("no-such-model"));
        assertNull(Models.findSpec("no-such-model"));
        // Round-trips: whatever the suite picker serves must be findable by its own id.
        assertNotNull(Models.findSpec(pick(Grade.XL).getId()));
        assertNotNull(Models.findSpec(new TestModelPicker().embeddingsSpec().getId()));
    }

    @Test
    void variantsGroupsByIdentity() {
        // One logical model, one spec per endpoint that serves it - for every identity in the
        // catalog, no endpoint appears twice; and a picked spec is among its identity's variants.
        for (ModelSpec spec : Models.all()) {
            var providers = Models.variants(spec.getIdentity()).stream().map(ModelSpec::getProviderKey).toList();
            assertEquals(providers.size(), Set.copyOf(providers).size(),
                    spec.getIdentity() + ": one spec per endpoint: " + providers);
        }
        ModelSpec picked = pick(Grade.XL);
        assertTrue(Models.variants(picked.getIdentity()).stream().anyMatch(s -> s.getId().equals(picked.getId())));
    }

    @Test
    void updateSpecOverridesAndInvalidatesCache() {
        ModelSpec original = pick(Grade.SMALL);
        int seededTpm = original.getTpm();
        StandardModelSpec learned = new StandardModelSpec();
        learned.setId(original.getId());
        learned.setIdentity(original.getIdentity());
        learned.setProviderKey(original.getProviderKey());
        learned.setWireModelId(original.getWireModelId());
        learned.setMaxContextTokens(original.getMaxContextTokens());
        learned.setMaxOutputTokens(original.getMaxOutputTokens());
        learned.setTpm(seededTpm + 123_456);
        learned.setRpm(original.getRpm());
        Models.updateSpec(original.getId(), learned);
        assertEquals(seededTpm + 123_456, Models.spec(original.getId()).getTpm());
        assertEquals(seededTpm + 123_456, Models.spec(original.getWireModelId()).getTpm(),
                "the override wins on the wire-id view too - the billing path must see learned limits,"
                        + " not the backend's seeded spec");
    }

    @Test
    void reloadDropsLearnedOverridesAndReResolvesFromTheDeployment() {
        // The moment a discovery rewrites models.json under a running process: reload() must
        // hand back the deployment's specs, not a learned override or a cached resolution.
        ModelSpec original = pick(Grade.SMALL);
        int seededTpm = original.getTpm();
        StandardModelSpec learned = new StandardModelSpec();
        learned.setId(original.getId());
        learned.setIdentity(original.getIdentity());
        learned.setProviderKey(original.getProviderKey());
        learned.setWireModelId(original.getWireModelId());
        learned.setMaxContextTokens(original.getMaxContextTokens());
        learned.setMaxOutputTokens(original.getMaxOutputTokens());
        learned.setTpm(seededTpm + 123_456);
        learned.setRpm(original.getRpm());
        Models.updateSpec(original.getId(), learned);
        assertEquals(seededTpm + 123_456, Models.spec(original.getId()).getTpm());
        Models.reload();
        assertEquals(seededTpm, Models.spec(original.getId()).getTpm(),
                "after reload the deployment's own spec answers again: the freshly written file"
                        + " carries the account's limits fresher than any learned header");
    }

    @Test
    void ceilingIsNeverARungAnywhere() {
        assertFalse(Grade.rungs().contains(Grade.CEILING),
                "the ladder proper omits CEILING - it is a seat's word, resolved at the picker gate");
        assertThrows(IllegalArgumentException.class, () -> Grade.CEILING.atLeast(Grade.SMALL),
                "CEILING has no rank and refuses ladder comparison");
        assertThrows(IllegalArgumentException.class, () -> Grade.SMALL.atLeast(Grade.CEILING));
        assertThrows(IllegalArgumentException.class, () -> Models.pool(Grade.CEILING),
                "no pool exists for CEILING - no catalog entry may carry it");
    }

    @Test
    void gradeIsPresentAndIdentityUniform() {
        // Every LLM entry carries a grade; embeddings and decision entries carry none; and
        // endpoint variants of one identity never disagree - grade is intrinsic to the model.
        Map<String, Grade> byIdentity = new HashMap<>();
        for (ModelSpec spec : Models.all()) {
            if (spec.kind() != ModelKind.LLM) {
                assertNull(spec.getGrade(), spec.getId() + ": " + spec.kind() + " specs are not on the ladder");
                continue;
            }
            assertNotNull(spec.getGrade(), spec.getId() + ": LLM entry without a grade");
            Grade prior = byIdentity.putIfAbsent(spec.getIdentity(), spec.getGrade());
            if (prior != null) {
                assertEquals(prior, spec.getGrade(), spec.getIdentity() + ": grade differs across endpoint variants");
            }
        }
    }

    @Test
    void poolReturnsExactlyTheRung() {
        for (ModelSpec spec : Models.pool(Grade.MEGA)) {
            assertEquals(Grade.MEGA, spec.getGrade(), spec.getId());
        }
        assertFalse(Models.pool(Grade.SMALL).isEmpty(), "the SMALL rung must not be empty");
        // Over-qualified is capable but not a candidate: no MEGA spec appears in a lower pool
        assertTrue(Models.pool(Grade.SMALL).stream().noneMatch(s -> s.getGrade() == Grade.MEGA));
    }

    @Test
    void embeddingsPoolCarriesOnlyEmbeddingsFamilySpecs() {
        Collection<ModelSpec> pool = Models.embeddingsPool();
        assertFalse(pool.isEmpty());
        for (ModelSpec spec : pool) {
            assertTrue(spec.isEmbeddings(), spec.getId());
        }
        assertTrue(pool.stream().anyMatch(s -> s.getId().equals(new TestModelPicker().embeddingsSpec().getId())),
                "the picker's embeddings channel must be in the pool");
        // Classification is provider-key-based: a dimensionless embeddings spec is still embeddings.
        StandardModelSpec dimensionless = new StandardModelSpec();
        dimensionless.setProviderKey("fake-embeddings");
        assertTrue(dimensionless.isEmbeddings(), "embeddings without a pinned dimension are still embeddings");
    }

    @Test
    void everyTestPickerGradeResolvesThroughTheGate() {
        // The suite's deployment picker must satisfy the gate for every grade it can be
        // asked for - the same check a real deployment's first resolution performs.
        TestModelPicker picker = new TestModelPicker();
        Situation situation = new Situation();
        situation.setEnvelope(spec -> true);
        for (Grade grade : Grade.rungs()) {
            ModelSpec spec = picker.provide(new Seat(ModelsTest.class, grade, ModelKind.LLM), situation);
            assertNotNull(spec, "grade did not resolve: " + grade);
            assertTrue(spec.getGrade().atLeast(grade), spec.getId() + " below declared floor " + grade);
        }
    }

    @Test
    void pickerEmbeddingsChannelResolvesAsEmbeddingsFamily() {
        // The frozen per-deployment embeddings declaration must land on the embeddings client
        // family with a callable wire id, and be reachable through its identity's variants.
        ModelSpec spec = new TestModelPicker().embeddingsSpec();
        assertTrue(spec.isEmbeddings(), spec.getId());
        assertNotNull(spec.getWireModelId(), spec.getId());
        assertTrue(Models.variants(spec.getIdentity()).stream().anyMatch(s -> s.getId().equals(spec.getId())));
    }

    @Test
    void testPickerNeverServesAnthropicDirect() {
        // Whatever the suite picker serves must resolve to AWS-credentialled endpoints, never to
        // anthropic-direct: a deployment with no ANTHROPIC key still has to boot. Non-Anthropic
        // grades pass trivially; the wall holds across the whole ladder without naming rungs.
        for (Grade grade : Grade.rungs()) {
            ModelSpec spec = pick(grade);
            assertFalse(spec.getProviderKey().equals("anthropic-direct"),
                    spec.getId() + " -> " + spec.getProviderKey());
        }
    }
}
