/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.conversation.*;


/**
 * Test-side spec derivation and conversation helpers. Tests never name catalog ids - models come
 * and go - so a test declares the PROPERTY it needs (a grade the suite picker serves, a data-share
 * spec, a retired spec, a provider family) and takes whatever qualifies. The only test-side homes
 * for concrete ids are {@link TestModelPicker} (a deployment picker, one of the two legal id homes)
 * and the fixture json catalogs.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
public final class TestModels {
    private TestModels() {}

    /** The spec the suite's deployment picker serves for a grade. */
    public static ModelSpec grade(Grade grade) {
        Situation situation = new Situation();
        situation.setEnvelope(spec -> true);
        return new TestModelPicker().provide(new Seat(TestModels.class, grade, ModelKind.LLM), situation);
    }

    /** The picker-served SMALL spec. */
    public static ModelSpec small() {
        return grade(Grade.SMALL);
    }

    /** The picker-served MICRO spec. */
    public static ModelSpec micro() {
        return grade(Grade.MICRO);
    }

    /** The suite's frozen embeddings declaration. */
    public static ModelSpec embeddings() {
        return new TestModelPicker().embeddingsSpec();
    }

    /** A live embeddings spec other than the frozen declaration - the corpus-migration hazard. */
    public static ModelSpec otherEmbeddings() {
        String frozen = embeddings().getId();
        return Models.all().stream()
                .filter(ModelSpec::isEmbeddings)
                .filter(spec -> spec.getStatus() == ModelStatus.OPEN)
                .filter(spec -> !spec.getId().equals(frozen))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the catalog carries a single live embeddings spec"));
    }

    /** A live spec the provider serves only under data sharing (the LAX-requiring route). */
    public static ModelSpec requiringLax() {
        return Models.all().stream()
                .filter(ModelSpec::requiresLax)
                .filter(spec -> spec.getStatus() == ModelStatus.OPEN)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no live data-share spec in the catalog"));
    }

    /** A retired LLM spec - still resolvable for billing, refused by the resolution gate. */
    public static ModelSpec deprecatedLlm() {
        return Models.all().stream()
                .filter(spec -> spec.getStatus() == ModelStatus.DEPRECATED)
                .filter(spec -> !spec.isEmbeddings())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no retired LLM spec in the catalog"));
    }

    /** A live LLM spec on the given provider key. */
    public static ModelSpec onProvider(String providerKey) {
        return Models.all().stream()
                .filter(spec -> providerKey.equals(spec.getProviderKey()))
                .filter(spec -> spec.getStatus() == ModelStatus.OPEN)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no live spec on provider " + providerKey));
    }

    /**
     * The endpoint variants of one live identity served on all three Anthropic routes (direct,
     * bedrock-runtime, Mantle), keyed by provider key. Route-wall tests (failover confinement,
     * envelope provider knobs) need every route present for a single identity.
     */
    public static java.util.Map<String, ModelSpec> tripleRoutedVariants() {
        for (ModelSpec spec : Models.all()) {
            if (spec.getStatus() != ModelStatus.OPEN || spec.isEmbeddings() || spec.requiresLax()) {
                continue;
            }
            java.util.Map<String, ModelSpec> byProvider = new java.util.HashMap<>();
            for (ModelSpec variant : Models.variants(spec.getIdentity())) {
                if (variant.getStatus() == ModelStatus.OPEN) {
                    byProvider.put(variant.getProviderKey(), variant);
                }
            }
            if (byProvider.keySet().containsAll(
                    java.util.Set.of("anthropic-direct", "anthropic-bedrock", "anthropic-bedrock-mantle"))) {
                return byProvider;
            }
        }
        throw new IllegalStateException("no live identity spans all three Anthropic routes");
    }

    /** A conversation wired around an already-resolved spec, the way client-internal contexts are built. */
    public static ConversationContext conversation(ModelSpec spec) {
        ConversationContext context = new ConversationContext();
        context.setModelBinding(ModelBinding.preResolved(spec));
        return context;
    }
}
