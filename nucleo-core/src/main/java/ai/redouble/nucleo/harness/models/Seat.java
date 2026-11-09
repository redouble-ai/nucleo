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
 * families entirely, named per deployment, never on the capability ladder. An LLM seat may
 * carry a {@link ModelRole} too, the application's choice of model for the kind of work the
 * seat does; its grade is then the role's.
 *
 * @param jobClass the declaring job's class (the seat's identity)
 * @param grade    the rung the work asks for, or {@link Grade#CEILING}; null for embeddings and decision seats
 * @param kind     which client family the seat asks for
 * @param role     the role the work declared, or null for a seat that declared a grade
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
public record Seat(Class<?> jobClass, Grade grade, ModelKind kind, ModelRole role) {
    public Seat {
        if (kind == null) {
            throw new IllegalArgumentException("A seat names the kind of model it asks for");
        }
        if (role != null && role.grade() != grade) {
            throw new IllegalArgumentException("Seat " + jobClass.getSimpleName() + " declares role " + role.name()
                    + " of grade " + role.grade() + " with grade " + grade + "; a role seat's grade is the role's");
        }
    }

    /** A seat that declared a grade and no role. */
    public Seat(Class<?> jobClass, Grade grade, ModelKind kind) {
        this(jobClass, grade, kind, null);
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
