/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

/**
 * Content guardrail: a pure function of the payload. Needs no context beyond the target
 * itself - no principal, no binding - which is why it is decidable on every execution
 * route, including ones with no flow at all.
 * <p>
 * Direction is a property of the guardrail CLASS, never a registration argument: an
 * {@code INPUT} guard polices what an untrusted caller is about to make the tool do;
 * an {@code OUTPUT} guard polices what the tool brought back before anyone consumes it.
 * An output guard runs after the tool's resources are released - for a transactional
 * tool that means after commit: output guards police information flow, they do not
 * undo effects.
 * <p>
 * Declared per invocation via {@link GuardedExecution#declareContentGuardrails()} as
 * constructed instances, so parameterized guards are first-class
 * (e.g. {@code new PayloadSizeCapGuardrail(this, 100_000)}).
 * Reference implementations: {@link PayloadSizeCapGuardrail} (INPUT),
 * {@link PIIDetectionGuardrail} (OUTPUT).
 *
 * @param <T> the type this guardrail validates
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public non-sealed interface ContentGuardrail<T> extends Guardrail<T> {
    /** The direction this guardrail polices - a fixed property of the class. */
    Direction direction();

    /** Which side of the gated execution a content guardrail polices. */
    enum Direction {
        /** Validates the gated job's input before resources are allocated. */
        INPUT,
        /** Validates the gated job's result after resources are released, before delivery. */
        OUTPUT
    }
}
