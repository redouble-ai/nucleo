/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

/**
 * The default database provider: a count and nothing else. A job that will use a connection
 * declares this provider in its requirements, admission grants it a permit only while the
 * datasource's count has room, and the job then works through its own stack - a Spring or JTA
 * transaction, an ORM session, a plain {@code DataSource} - which takes the connection from its
 * own pool whenever it needs it and gives it back the same way. The runtime counts declared
 * use against the host's bound and holds nothing, so it depends on no database API.
 *
 * <p>The bound is the host's statement: how many jobs may use a connection of this datasource
 * at once. It must leave the pool room for everything else that uses it.
 *
 * <p>A job unwraps nothing: the handle carries no connection. The transaction verbs do
 * nothing, since the job's stack owns its transactions; a job that runs one declares none on
 * its requirements and lets its stack demarcate it. Close and abort return nothing to a pool,
 * since nothing was taken from one; the permit goes back when the job's grant is released.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public class CountingDBResourceProvider implements DBResourceProvider<Void> {
    private final String name;
    private final DatabaseGate admission;

    /**
     * @param name          the datasource's identity: the health row {@code db:<name>} and the
     *                      name jobs look the provider up by
     * @param maxConcurrent how many jobs may use a connection of the datasource at once
     */
    public CountingDBResourceProvider(String name, int maxConcurrent) {
        this.name = name;
        this.admission = new DatabaseGate(name, maxConcurrent);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RateLimiter<Void> admission() {
        return admission;
    }

    /** A handle for the admitted job; nothing is checked out. */
    @Override
    public DBManagedResource<Void> acquire(boolean readOnly) {
        return new Handle();
    }

    /** Nothing: the job's stack begins its own transactions. */
    @Override
    public void begin(DBManagedResource<Void> resource) {
        cast(resource);
    }

    /** Nothing: the job's stack commits its own transactions. */
    @Override
    public void commit(DBManagedResource<Void> resource) {
        cast(resource);
    }

    /** Returns at once: the job's stack settles its own commits. */
    @Override
    public void awaitCompletion(DBManagedResource<Void> resource) {
        cast(resource);
    }

    /** Nothing: the job's stack rolls back its own transactions. */
    @Override
    public void rollback(DBManagedResource<Void> resource) {
        cast(resource);
    }

    /** Nothing goes back to a pool, since nothing came from one. */
    @Override
    public void close(DBManagedResource<Void> resource) {
        cast(resource);
    }

    /** Nothing to sever: the job's stack holds its connection, and the job's interrupt ends its use. */
    @Override
    public void abort(DBManagedResource<Void> resource) {
        cast(resource);
    }

    private Handle cast(DBManagedResource<Void> resource) {
        if (!(resource instanceof Handle h) || h.provider() != this) {
            throw new IllegalArgumentException("Handle was not produced by the counting provider '" + name + "': " + resource);
        }
        return h;
    }

    private final class Handle implements DBManagedResource<Void> {
        private CountingDBResourceProvider provider() {
            return CountingDBResourceProvider.this;
        }

        /** Nothing: the job reaches its connection through its own stack. */
        @Override
        public Void unwrap() {
            return null;
        }
    }
}
