/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.spring;

import ai.redouble.nucleo.harness.errors.*;
import jakarta.persistence.*;
import org.hibernate.*;
import org.hibernate.resource.jdbc.spi.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.orm.jpa.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

import javax.sql.*;
import java.sql.Connection;
import java.sql.*;

/**
 * A Hibernate session held for one job under a {@link JpaTransactionManager}, bound to the
 * job's thread as the {@link EntityManagerHolder} the manager looks up. The session opens in
 * {@link PhysicalConnectionHandlingMode#DELAYED_ACQUISITION_AND_HOLD} with its connection
 * checked out at once, so it keeps that connection across every transaction the job runs.
 *
 * <p>Between transactions the session's connection is also bound for the manager's datasource,
 * so plain JDBC beside JPA runs on it; the manager binds that datasource itself while a
 * transaction runs, and refuses one bound ahead of it, so the binding steps aside at
 * {@link #beforeTransaction} and returns at {@link #afterTransaction}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
final class HeldEntityManager implements HeldResource {
    private final EntityManagerFactory factory;
    private final DataSource dataSource;
    private final Session session;
    private final EntityManagerHolder holder;
    private final Connection connection;
    private final ConnectionHolder connectionHolder;
    private final boolean originalReadOnly;

    /**
     * Refuses a manager this class cannot hold a session for: anything but a
     * {@link JpaTransactionManager} over Hibernate.
     */
    static void requireSupported(PlatformTransactionManager transactionManager) {
        if (!(transactionManager instanceof JpaTransactionManager jpa)) {
            throw new IllegalArgumentException("A Spring transaction provider needs a manager over a DataSource or a JPA "
                    + "EntityManagerFactory; " + transactionManager.getClass().getName() + " manages neither");
        }
        try {
            jpa.getEntityManagerFactory().unwrap(SessionFactory.class);
        }
        catch (PersistenceException e) {
            throw new IllegalArgumentException("A Spring transaction provider holds a Hibernate session for each job; the JPA provider behind "
                    + jpa.getEntityManagerFactory().getClass().getName() + " is not Hibernate", e);
        }
    }

    HeldEntityManager(PlatformTransactionManager transactionManager, boolean readOnly) {
        JpaTransactionManager jpa = (JpaTransactionManager) transactionManager;
        this.factory = jpa.getEntityManagerFactory();
        this.dataSource = jpa.getDataSource();
        this.session = factory.unwrap(SessionFactory.class).withOptions()
                .connectionHandlingMode(PhysicalConnectionHandlingMode.DELAYED_ACQUISITION_AND_HOLD)
                .openSession();
        try {
            session.setDefaultReadOnly(readOnly);
            Connection[] held = new Connection[1];
            boolean[] original = new boolean[1];
            session.doWork(c -> {
                held[0] = c;
                original[0] = c.isReadOnly();
                c.setReadOnly(readOnly);
            });
            this.connection = held[0];
            this.originalReadOnly = original[0];
        }
        catch (RuntimeException e) {
            UncorrectableRuntimeLLMException failure = new UncorrectableRuntimeLLMException("Could not open a connected session: " + e.getMessage(), e);
            try {
                session.close();
            }
            catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        this.holder = new EntityManagerHolder(session);
        this.connectionHolder = dataSource == null ? null : new ConnectionHolder(connection);
        TransactionSynchronizationManager.bindResource(factory, holder);
        bindConnection();
    }

    @Override
    public void beforeTransaction() {
        unbindConnection();
    }

    @Override
    public void afterTransaction() {
        bindConnection();
    }

    @Override
    public void release(boolean onOwner, boolean aborted) {
        if (onOwner) {
            unbindConnection();
            if (dataSource != null) {
                TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
            }
            if (TransactionSynchronizationManager.getResource(factory) == holder) {
                TransactionSynchronizationManager.unbindResource(factory);
            }
        }
        UncorrectableRuntimeLLMException failure = null;
        if (!aborted) {
            try {
                if (session.getTransaction().isActive()) {
                    session.getTransaction().rollback();
                }
                connection.setReadOnly(originalReadOnly);
            }
            catch (RuntimeException | SQLException e) {
                failure = new UncorrectableRuntimeLLMException("Could not reset a session before closing it: " + e.getMessage(), e);
            }
        }
        try {
            session.close();
        }
        catch (RuntimeException e) {
            if (failure == null) {
                failure = new UncorrectableRuntimeLLMException("Could not close a session: " + e.getMessage(), e);
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
            connection.abort(HeldConnection.ABORT_EXECUTOR);
        }
        catch (SQLException e) {
            throw new UncorrectableRuntimeLLMException("Could not abort a connection: " + e.getMessage(), e);
        }
    }

    private void bindConnection() {
        if (connectionHolder != null && !TransactionSynchronizationManager.hasResource(dataSource)) {
            TransactionSynchronizationManager.bindResource(dataSource, connectionHolder);
        }
    }

    private void unbindConnection() {
        if (connectionHolder != null && TransactionSynchronizationManager.getResource(dataSource) == connectionHolder) {
            TransactionSynchronizationManager.unbindResource(dataSource);
        }
    }
}
