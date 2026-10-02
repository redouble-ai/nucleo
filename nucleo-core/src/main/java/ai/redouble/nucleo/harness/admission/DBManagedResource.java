/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.*;

/**
 * Opaque handle issued by a {@link DBResourceProvider#acquire} call. Holds
 * all per-job state the provider needs: the underlying session or connection,
 * latches for commit-completion signaling, and transaction state bookkeeping.
 * The admission permit the handle was materialized under is not the handle's:
 * {@link Admission} holds it in the job's grant and returns it at release.
 *
 * <p>Jobs never construct handles directly. They obtain one by calling
 * {@link JobResources#get} on the provider they declared, and call
 * {@link #unwrap} to reach the stack-specific object they need.
 *
 * @param <T> the stack-specific type (e.g. Hibernate Session,
 *            JDBC Connection, JPA EntityManager) exposed via {@link #unwrap}
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-19)
 */
public interface DBManagedResource<T> {

    /**
     * Returns the stack-specific object jobs need to do their work.
     * The returned object is owned by the provider; jobs must not close
     * it directly. It is valid until the surrounding
     * {@link DBResourceProvider#close} or
     * {@link DBResourceProvider#abort} is invoked.
     */
    T unwrap();
}
