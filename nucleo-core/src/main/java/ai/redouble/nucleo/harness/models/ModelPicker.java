/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;


import java.util.*;

/**
 * The deployment's model-resolution policy: turns a declared need into a concrete
 * {@link ModelSpec}. One picker per process, configured via {@code ModelSettings.pickerClass},
 * consulted ONLY by the harness through {@code ModelPickers.resolve} - never by call sites
 * directly - so the gate (envelope, grade floor, capability fit) applies to every answer.
 *
 * <p>Total and side-effect-free toward the caller's state. The situation carries
 * everything the call site knows (occasion, history, demand, the sealed envelope);
 * ambient reads of the {@link Models} catalog and rate-limiter state are the picker's
 * own business. Stickiness is not a framework mechanism: the situation carries the
 * prior spec and a policy that wants stickiness returns it.
 *
 * <p>An interface deliberately: implementations range from a lambda pinning a grade map
 * to stateful classes (memoized health tables, sticky A/B hashing), composed by ordinary
 * delegation - a deployment picker wrapping another for the seats it does not care
 * about. There is no framework combinator machinery.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
@FunctionalInterface
public interface ModelPicker {
    ModelSpec provide(Seat seat, Situation situation);

    /**
     * An availability observation for a spec, pushed by the harness (the probe doer via
     * {@code ModelPickers.recordProbe}). Returns the observation this one displaced for
     * the same spec, so the caller can compute drift in the same motion. Ignoring
     * observations is legal - the default keeps no state and returns null; a stateful
     * picker ({@link AbstractModelPicker}) folds them into its policy.
     */
    default ProbeOutcome recordProbe(ProbeOutcome outcome) {
        return null;
    }

    /**
     * The picker's standing grade pins, for reporting surfaces that mark which catalog
     * entries production actually rides. Declarative only - resolution never reads it.
     * Empty means the picker declares none.
     */
    default Map<Grade, ModelSpec> describePins() {
        return Map.of();
    }

    /**
     * The strongest grade this deployment serves: the dual of a seat's floor. A seat
     * that wants the best model the deployment has - every chat, where a human is
     * waiting on the answer - declares this instead of naming a rung, so the same
     * class rides MEGA where the deployment pins one and XL where it does not,
     * with no per-app subclass knowing which. Such a seat declares {@link Grade#CEILING},
     * and {@code ModelPickers.resolveWith} translates it to this grade before the picker
     * sees the seat; the gate then applies as for any declaration. A picker derives it
     * from what it can serve; {@link DefaultModelPicker} takes the highest rung with a pin
     * or a callable entry. Null (the default) means the picker serves no rung, and every
     * best-available seat refuses.
     */
    default Grade ceiling() {
        return null;
    }

    /**
     * The deployment's embeddings model: a CONFIGURATION CONSTANT, never a per-call
     * pick. Stored vectors are only comparable to vectors from the model that produced
     * them, so an embeddings model that changes ad-hoc silently corrupts every
     * similarity search - which is why embeddings do not flow through
     * {@link #provide(Seat, Situation)} at all: the harness resolves them through this
     * declaration and freezes the first answer for the process lifetime. Null (the
     * default) means the deployment declares none and every embeddings need refuses.
     */
    default ModelSpec embeddingsSpec() {
        return null;
    }

    /**
     * The deployment's decision model: the one entry that serves every decision seat. Like
     * embeddings, a declaration rather than a per-call pick, because decision models are not
     * on the capability ladder and a deployment runs one of them; unlike embeddings it is not
     * frozen, since nothing stored depends on which model answered. Null (the default) means
     * the deployment declares none and every decision need refuses.
     */
    default ModelSpec decisionSpec() {
        return null;
    }
}
