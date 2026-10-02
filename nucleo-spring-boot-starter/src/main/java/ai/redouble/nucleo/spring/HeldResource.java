/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.spring;

/**
 * What {@link SpringTransactionResourceProvider} holds for one job and binds to its thread: a
 * JDBC connection under a datasource manager, a Hibernate session under a JPA manager. One
 * implementation per resource-factory kind, so the JPA classes are loaded only by an
 * application that uses JPA.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
interface HeldResource {

    /** On the job's thread, before the manager begins a transaction. */
    void beforeTransaction();

    /** On the job's thread, once the manager completed a transaction, however it ended. */
    void afterTransaction();

    /**
     * Returns the connection to the pool. On the job's thread the bindings are removed first;
     * elsewhere they are left to die with that thread. After an abort nothing is reset.
     */
    void release(boolean onOwner, boolean aborted);

    /** Severs the connection without waiting on the driver. */
    void abort();
}
