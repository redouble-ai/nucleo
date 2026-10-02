/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.spring;

import ai.redouble.nucleo.harness.errors.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;

import javax.sql.*;
import java.sql.*;
import java.util.concurrent.*;

/**
 * A connection held for one job under a datasource transaction manager, bound to the job's
 * thread as the {@link ConnectionHolder} the manager and {@link DataSourceUtils} look up. The
 * manager reuses a holder it did not bind and never releases it, so every transaction the job
 * runs, and every access outside one, lands on this connection.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
final class HeldConnection implements HeldResource {
    static final Executor ABORT_EXECUTOR = task -> Thread.ofVirtual().name("spring-tx-abort").start(task);

    private final DataSource dataSource;
    private final Connection connection;
    private final ConnectionHolder holder;
    private final boolean readOnly;
    private final boolean originalReadOnly;

    HeldConnection(DataSource dataSource, boolean readOnly) {
        this.dataSource = dataSource;
        this.readOnly = readOnly;
        try {
            this.connection = dataSource.getConnection();
        }
        catch (SQLException e) {
            throw new UncorrectableRuntimeLLMException("Could not check out a connection: " + e.getMessage(), e);
        }
        try {
            this.originalReadOnly = connection.isReadOnly();
            connection.setReadOnly(readOnly);
        }
        catch (SQLException e) {
            UncorrectableRuntimeLLMException failure = new UncorrectableRuntimeLLMException("Could not prepare a connection: " + e.getMessage(), e);
            closeQuietlyInto(failure);
            throw failure;
        }
        this.holder = new ConnectionHolder(connection);
        TransactionSynchronizationManager.bindResource(dataSource, holder);
    }

    @Override
    public void beforeTransaction() {
    }

    /** The manager resets read-only after a read-only transaction; the handle's hint stands. */
    @Override
    public void afterTransaction() {
        try {
            if (!connection.isClosed()) {
                connection.setReadOnly(readOnly);
            }
        }
        catch (SQLException e) {
            throw new UncorrectableRuntimeLLMException("Could not restore the read-only hint after a transaction: " + e.getMessage(), e);
        }
    }

    @Override
    public void release(boolean onOwner, boolean aborted) {
        if (onOwner && TransactionSynchronizationManager.getResource(dataSource) == holder) {
            TransactionSynchronizationManager.unbindResource(dataSource);
        }
        UncorrectableRuntimeLLMException failure = null;
        if (!aborted) {
            try {
                if (!connection.isClosed()) {
                    if (!connection.getAutoCommit()) {
                        connection.rollback();
                        connection.setAutoCommit(true);
                    }
                    connection.setReadOnly(originalReadOnly);
                }
            }
            catch (SQLException e) {
                failure = new UncorrectableRuntimeLLMException("Could not reset a connection before returning it: " + e.getMessage(), e);
            }
        }
        try {
            connection.close();
        }
        catch (SQLException e) {
            if (failure == null) {
                failure = new UncorrectableRuntimeLLMException("Could not return a connection: " + e.getMessage(), e);
            }
            else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void abort() {
        try {
            connection.abort(ABORT_EXECUTOR);
        }
        catch (SQLException e) {
            throw new UncorrectableRuntimeLLMException("Could not abort a connection: " + e.getMessage(), e);
        }
    }

    private void closeQuietlyInto(UncorrectableRuntimeLLMException failure) {
        try {
            connection.close();
        }
        catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
