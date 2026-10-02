/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.jdbc;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import javax.sql.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * A {@link DBResourceProvider} over any {@link DataSource}: a job gets a plain
 * {@link Connection}, checked out when the job is admitted and held until it finishes. The
 * pool behind the datasource is the host's - Hikari, Agroal, DBCP, UCP, a container's JNDI
 * pool - and this class never reads, sizes or tunes it.
 *
 * <p>The admission bound is the host's statement: {@code maxConcurrent} permits on a
 * {@link DatabaseGate} named {@code db:<name>}, one held connection per permit. It must not
 * exceed the connections the pool reserves for jobs. When other code shares the pool - web
 * requests, a scheduler - and has drained it, {@link #acquire} waits inside the pool for as
 * long as the pool's own checkout timeout allows, and a checkout that times out fails the job
 * uncorrectably.
 *
 * <p>Outside a transaction the connection is in auto-commit; {@link #begin} turns it off and
 * {@link #commit} / {@link #rollback} turn it back on, so a job may run several transactions
 * on the one connection. {@link #awaitCompletion} returns at once: {@link Connection#commit()}
 * returns only after the server acknowledged the commit, and from then on every other
 * connection to the same server sees it. A deployment that reads from a replica fronts it with
 * a provider of its own.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public class JdbcResourceProvider implements DBResourceProvider<Connection> {

    /** An abort hands the socket teardown to a thread of its own, never to the caller's. */
    private static final Executor ABORT_EXECUTOR = task -> Thread.ofVirtual().name("jdbc-abort").start(task);

    private final String name;
    private final DataSource dataSource;
    private final DatabaseGate admission;

    /**
     * @param name          the datasource's identity: the health row {@code db:<name>} and the
     *                      name jobs look the provider up by
     * @param dataSource    the host's datasource, whatever pool is behind it
     * @param maxConcurrent how many jobs may hold a connection at once
     */
    public JdbcResourceProvider(String name, DataSource dataSource, int maxConcurrent) {
        this.name = name;
        this.dataSource = dataSource;
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

    @Override
    public DBManagedResource<Connection> acquire(boolean readOnly) {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        }
        catch (SQLException e) {
            throw new UncorrectableRuntimeLLMException("Could not check out a connection from datasource '" + name + "': " + e.getMessage(), e);
        }
        try {
            Handle handle = new Handle(connection, connection.getAutoCommit(), connection.isReadOnly());
            connection.setAutoCommit(true);
            connection.setReadOnly(readOnly);
            return handle;
        }
        catch (SQLException e) {
            UncorrectableRuntimeLLMException failure = new UncorrectableRuntimeLLMException(
                    "Could not prepare a connection from datasource '" + name + "': " + e.getMessage(), e);
            try {
                connection.close();
            }
            catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public void begin(DBManagedResource<Connection> resource) {
        Handle h = cast(resource);
        try {
            h.connection.setAutoCommit(false);
        }
        catch (SQLException e) {
            throw new UncorrectableRuntimeLLMException("Could not begin a transaction on datasource '" + name + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void commit(DBManagedResource<Connection> resource) {
        Handle h = cast(resource);
        try {
            if (!h.connection.getAutoCommit()) {
                h.connection.commit();
                h.connection.setAutoCommit(true);
            }
        }
        catch (SQLException e) {
            throw new UncorrectableRuntimeLLMException("Commit failed on datasource '" + name + "': " + e.getMessage(), e);
        }
    }

    /** Returns at once: a JDBC commit has been acknowledged by the server when it returns. */
    @Override
    public void awaitCompletion(DBManagedResource<Connection> resource) {
        cast(resource);
    }

    /** Idempotent: nothing to do without an open transaction, or after an abort. */
    @Override
    public void rollback(DBManagedResource<Connection> resource) {
        Handle h = cast(resource);
        if (h.aborted.get()) {
            return;
        }
        try {
            if (!h.connection.isClosed() && !h.connection.getAutoCommit()) {
                h.connection.rollback();
                h.connection.setAutoCommit(true);
            }
        }
        catch (SQLException e) {
            throw new UncorrectableRuntimeLLMException("Rollback failed on datasource '" + name + "': " + e.getMessage(), e);
        }
    }

    /**
     * Rolls back an open transaction, restores the auto-commit and read-only state the pool
     * handed out, and returns the connection to the pool. The connection is closed even when
     * the rollback or the restore fails; the failure is thrown afterwards. After an abort there
     * is no session left to roll back or restore: the close only hands the severed connection
     * back, and the pool discards it.
     */
    @Override
    public void close(DBManagedResource<Connection> resource) {
        Handle h = cast(resource);
        if (!h.closed.compareAndSet(false, true)) {
            return;
        }
        UncorrectableRuntimeLLMException failure = null;
        try {
            if (!h.aborted.get() && !h.connection.isClosed()) {
                if (!h.connection.getAutoCommit()) {
                    h.connection.rollback();
                }
                h.connection.setAutoCommit(h.originalAutoCommit);
                h.connection.setReadOnly(h.originalReadOnly);
            }
        }
        catch (SQLException e) {
            failure = new UncorrectableRuntimeLLMException("Could not reset a connection of datasource '" + name + "' before returning it: " + e.getMessage(), e);
        }
        try {
            h.connection.close();
        }
        catch (SQLException e) {
            if (failure == null) {
                failure = new UncorrectableRuntimeLLMException("Could not return a connection to datasource '" + name + "': " + e.getMessage(), e);
            }
            else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Aborts the physical connection through {@link Connection#abort}, on a thread of its own. */
    @Override
    public void abort(DBManagedResource<Connection> resource) {
        Handle h = cast(resource);
        if (!h.aborted.compareAndSet(false, true)) {
            return;
        }
        try {
            h.connection.abort(ABORT_EXECUTOR);
        }
        catch (SQLException e) {
            throw new UncorrectableRuntimeLLMException("Could not abort a connection of datasource '" + name + "': " + e.getMessage(), e);
        }
    }

    private Handle cast(DBManagedResource<Connection> resource) {
        if (!(resource instanceof Handle h) || h.provider() != this) {
            throw new IllegalArgumentException("Handle was not produced by the JDBC provider '" + name + "': " + resource);
        }
        return h;
    }

    private final class Handle implements DBManagedResource<Connection> {
        private final Connection connection;
        private final boolean originalAutoCommit;
        private final boolean originalReadOnly;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean aborted = new AtomicBoolean(false);

        private Handle(Connection connection, boolean originalAutoCommit, boolean originalReadOnly) {
            this.connection = connection;
            this.originalAutoCommit = originalAutoCommit;
            this.originalReadOnly = originalReadOnly;
        }

        private JdbcResourceProvider provider() {
            return JdbcResourceProvider.this;
        }

        @Override
        public Connection unwrap() {
            return connection;
        }
    }
}
