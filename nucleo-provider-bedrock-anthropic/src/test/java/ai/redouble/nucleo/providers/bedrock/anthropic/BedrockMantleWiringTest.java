/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock.anthropic;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.providers.anthropic.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the Bedrock Mantle endpoint wiring: catalog specs, provider discovery, and the two
 * properties that make the surfaces non-interchangeable - bare model ids (each surface rejects the
 * other's form) and a distinct dialect that still bills as BEDROCK.
 *
 * <p>No network: this pins the wiring, not the inference. A live call through the client is a
 * separate manual check. WHICH models the route serves is catalog data validated live by the
 * availability probe (Mantle's Anthropic route serves only newer releases - older ones return
 * "does not exist" there), so no spec ids are named here; every check derives its subjects from
 * the catalog.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-16)
 */
class BedrockMantleWiringTest {

    private static final String PROVIDER = "anthropic-bedrock-mantle";

    private final ModelsBackend backend = new JsonModelsBackend();

    private List<ModelSpec> mantleSpecs() {
        return backend.all().stream().filter(s -> PROVIDER.equals(s.getProviderKey())).toList();
    }

    @Test
    void mantleSpecsUseBareModelIds() {
        // The legacy path needs a us./global. inference profile; Mantle rejects that form outright.
        for (ModelSpec spec : mantleSpecs()) {
            String wire = spec.getWireModelId();
            assertTrue(wire.startsWith("anthropic."), wire);
            assertFalse(wire.startsWith("us.") || wire.startsWith("global."), wire);
        }
    }

    @Test
    void mantleSharesIdentityAndPricingWithItsLegacyTwin() {
        // Same logical model on another endpoint: identity must match so variants() groups them,
        // and pricing must match so a cutover does not silently change reported cost. A requiresLax
        // model is the exception in the other direction: it runs only under the LAX Mantle project,
        // and the legacy bedrock-runtime path has no workspace binding, so a -bedrock twin would be
        // a spec that can never be called - it must NOT exist.
        for (ModelSpec mantle : mantleSpecs()) {
            ModelSpec twin = backend.spec(mantle.getId().replace("-mantle", "-bedrock"));
            if (mantle.requiresLax()) {
                assertNull(twin, mantle.getId() + " requires LAX and must have no bedrock-runtime twin");
                continue;
            }
            assertNotNull(twin, mantle.getId());
            assertEquals(twin.getIdentity(), mantle.getIdentity(), mantle.getId());
            assertEquals(twin.getCurrency(), mantle.getCurrency(), mantle.getId());
            assertEquals(twin.getInputPricePerMillion(), mantle.getInputPricePerMillion(), mantle.getId());
            assertEquals(twin.getOutputPricePerMillion(), mantle.getOutputPricePerMillion(), mantle.getId());
        }
    }

    @Test
    void requiresLaxAppearsOnlyOnTheMantleRoute() {
        // Ground truth for WHO requires provider_data_share is the model's allowed_modes in the
        // Mantle catalog - external data, validated live by the availability probe. What the
        // catalog can promise structurally: only Mantle carries a workspace binding, so a
        // requiresLax flag on any other route is a spec that can never be called.
        for (ModelSpec spec : backend.all()) {
            if (spec.requiresLax()) {
                assertEquals(PROVIDER, spec.getProviderKey(),
                        spec.getId() + ": only the Mantle route can bind the LAX workspace project");
            }
        }
    }

    /**
     * Every grade the Mantle route serves keeps at least one live spec. A deployment that pins a
     * grade to Mantle cannot resolve a retired spec, so a rung losing its last undeprecated Mantle
     * entry breaks resolution there. Which identities are retired is catalog data; the
     * deprecation contract itself lives in nucleo-core's {@code DeprecatedModelTest}.
     */
    @Test
    void everyServedGradeKeepsALiveMantleSpec() {
        assertFalse(mantleSpecs().isEmpty(), "the Mantle route serves nothing");
        Map<Grade, List<String>> live = mantleSpecs().stream()
                .filter(spec -> spec.getStatus() == ModelStatus.OPEN)
                .collect(Collectors.groupingBy(ModelSpec::getGrade,
                                               Collectors.mapping(ModelSpec::getId, Collectors.toList())));
        for (Grade served : mantleSpecs().stream().map(ModelSpec::getGrade).distinct().toList()) {
            assertFalse(live.getOrDefault(served, List.of()).isEmpty(),
                        "the Mantle route serves grade " + served + " with nothing live");
        }
    }

    @Test
    void providerIsDiscoveredAndTypedToTheMantleClient() {
        ClientProvider<?> provider = ClientProviders.get(PROVIDER);
        assertNotNull(provider, "classpath scan must find the Mantle provider");
        assertInstanceOf(AnthropicBedrockMantleProvider.class, provider);
    }

    @Test
    void dialectIsDistinctButBillsAsBedrock() {
        // A separate dialect keeps the surfaces legible in logs; the shared providerKey keeps
        // billing multipliers and the recorded provider value untouched.
        assertNotEquals(APIDialect.BEDROCK_ANTHROPIC.id(), APIDialect.BEDROCK_MANTLE.id());
        assertEquals(APIDialect.BEDROCK_ANTHROPIC.providerKey(), APIDialect.BEDROCK_MANTLE.providerKey());
    }

    @Test
    void anthropicResolverAcceptsTheMantleProviderKey() {
        // The resolver gates on an "anthropic" provider-key prefix; every spec on this route must pass.
        for (ModelSpec spec : mantleSpecs()) {
            assertNotNull(AnthropicModelResolver.resolve(spec), spec.getId());
        }
    }
}
