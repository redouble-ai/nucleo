/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;


/**
 * Who is asking for a model: the identity of the declaring workload. Seats self-identify
 * by job class - thinker classes belong to applications, so per-app picker policy falls
 * out of per-seat matching without any registry.
 *
 * <p>Only an LLM seat has a grade: embeddings and decision models are different client
 * families entirely, named per deployment, never on the capability ladder.
 *
 * @param jobClass the declaring job's class (the seat's identity)
 * @param grade    the declared capability floor; null for embeddings and decision seats
 * @param kind     which client family the seat asks for
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
public record Seat(Class<?> jobClass, Grade grade, ModelKind kind) {
    public Seat {
        if (kind == null) {
            throw new IllegalArgumentException("A seat names the kind of model it asks for");
        }
    }

    /** Whether this seat asks for an embeddings model. */
    public boolean embeddings() {
        return kind == ModelKind.EMBEDDINGS;
    }

    /** Whether this seat asks for a decision model. */
    public boolean decision() {
        return kind == ModelKind.DECISION;
    }
}
