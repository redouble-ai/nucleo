/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.spring;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

import javax.sql.*;
import java.util.concurrent.atomic.*;

/**
 * A {@link DBResourceProvider} over the application's own Spring
 * {@link ResourceTransactionManager}, so the data access a Spring application already has -
 * repositories, {@code JdbcTemplate}, {@code @Transactional} services - runs inside a job on
 * the connection the job was admitted for. A job declares the provider and calls its beans;
 * what it unwraps is the {@link TransactionStatus} of the transaction under way, null outside
 * one.
 *
 * <p>At {@link #acquire} the provider checks out the connection and binds it to the job's
 * thread the way Spring's open-in-view support binds a request's: a {@code ConnectionHolder}
 * under a {@code DataSourceTransactionManager}'s datasource, an {@code EntityManagerHolder}
 * over a Hibernate session held for the job's life under a {@code JpaTransactionManager}'s
 * factory. The transaction manager then finds it for every transaction the job runs, and
 * data access outside a transaction finds it too, so the job never goes back to the pool. Under
 * JPA the session's connection is also bound for the manager's datasource between
 * transactions, so plain JDBC beside JPA stays on it as well. The one exception is a
 * transaction that suspends the job's ({@code REQUIRES_NEW}): Spring runs it on a second
 * connection from the pool, outside the admission bound, and hands that back when it ends.
 *
 * <p>Spring binds transactions to a thread. {@link #begin}, {@link #commit} and
 * {@link #rollback} run on the job's thread, where the dispatcher calls them. A close on any
 * other thread - the dispatcher's timeout enforcement - cannot reach that thread's bindings;
 * it returns the connection to the pool directly, and the bindings die with the job's
 * virtual thread. {@link #awaitCompletion} returns at once: every resource transaction
 * manager commits through a JDBC commit the server has acknowledged when it returns.
 *
 * <p>The admission bound {@code maxConcurrent} must not exceed the connections the pool
 * reserves for jobs; the pool is the application's and is never read or tuned here.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public class SpringTransactionResourceProvider implements DBResourceProvider<TransactionStatus> {
    private final String name;
    private final PlatformTransactionManager transactionManager;
    private final Object resourceFactory;
    private final DatabaseGate admission;

    /**
     * @param name               the datasource's identity: the health row {@code db:<name>}
     *                           and the name jobs look the provider up by
     * @param transactionManager the application's manager: its resource factory is a
     *                           {@link DataSource}, or a JPA {@code EntityManagerFactory} whose
     *                           provider is Hibernate
     * @param maxConcurrent      how many jobs may hold a connection at once
     * @throws IllegalArgumentException for a resource factory of any other kind
     */
    public SpringTransactionResourceProvider(String name, ResourceTransactionManager transactionManager, int maxConcurrent) {
        this.name = name;
        this.transactionManager = transactionManager;
        this.resourceFactory = transactionManager.getResourceFactory();
        if (!(resourceFactory instanceof DataSource)) {
            HeldEntityManager.requireSupported(transactionManager);
        }
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
    public DBManagedResource<TransactionStatus> acquire(boolean readOnly) {
        HeldResource held = resourceFactory instanceof DataSource dataSource
                ? new HeldConnection(dataSource, readOnly)
                : new HeldEntityManager(transactionManager, readOnly);
        return new Handle(held, readOnly, Thread.currentThread());
    }

    @Override
    public void begin(DBManagedResource<TransactionStatus> resource) {
        Handle h = owned(resource, "begin");
        if (h.closed.get()) {
            throw new UncorrectableRuntimeLLMException("Cannot begin a transaction on a closed handle of datasource '" + name + "'");
        }
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED);
        definition.setReadOnly(h.readOnly);
        definition.setName("nucleo:" + name);
        h.held.beforeTransaction();
        try {
            h.status = transactionManager.getTransaction(definition);
        }
        catch (TransactionException e) {
            h.held.afterTransaction();
            throw new UncorrectableRuntimeLLMException("Could not begin a transaction on datasource '" + name + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void commit(DBManagedResource<TransactionStatus> resource) {
        Handle h = owned(resource, "commit");
        TransactionStatus status = h.status;
        if (status == null || status.isCompleted()) {
            return;
        }
        try {
            transactionManager.commit(status);
        }
        catch (TransactionException e) {
            throw new UncorrectableRuntimeLLMException("Commit failed on datasource '" + name + "': " + e.getMessage(), e);
        }
        finally {
            h.held.afterTransaction();
        }
    }

    /** Returns at once: the commit was acknowledged by the server when {@link #commit} returned. */
    @Override
    public void awaitCompletion(DBManagedResource<TransactionStatus> resource) {
        cast(resource);
    }

    /** Idempotent: nothing to do without a transaction under way, or after an abort. */
    @Override
    public void rollback(DBManagedResource<TransactionStatus> resource) {
        Handle h = owned(resource, "rollback");
        TransactionStatus status = h.status;
        if (h.aborted.get() || status == null || status.isCompleted()) {
            return;
        }
        try {
            transactionManager.rollback(status);
        }
        catch (TransactionException e) {
            throw new UncorrectableRuntimeLLMException("Rollback failed on datasource '" + name + "': " + e.getMessage(), e);
        }
        finally {
            h.held.afterTransaction();
        }
    }

    /**
     * On the job's thread: rolls back a transaction under way through the manager, unbinds
     * what {@link #acquire} bound, and returns the connection. On any other thread, or after
     * an abort: returns the connection directly, clearing the job thread's transaction state
     * when this is that thread.
     */
    @Override
    public void close(DBManagedResource<TransactionStatus> resource) {
        Handle h = cast(resource);
        if (!h.closed.compareAndSet(false, true)) {
            return;
        }
        boolean onOwner = Thread.currentThread() == h.owner;
        UncorrectableRuntimeLLMException failure = null;
        TransactionStatus status = h.status;
        if (onOwner && status != null && !status.isCompleted()) {
            if (h.aborted.get()) {
                TransactionSynchronizationManager.clear();
            }
            else {
                try {
                    transactionManager.rollback(status);
                }
                catch (TransactionException e) {
                    failure = new UncorrectableRuntimeLLMException("Rollback on close failed on datasource '" + name + "': " + e.getMessage(), e);
                }
            }
        }
        try {
            h.held.release(onOwner, h.aborted.get());
        }
        catch (RuntimeException e) {
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

    /** Aborts the held connection through {@link java.sql.Connection#abort}, on a thread of its own. */
    @Override
    public void abort(DBManagedResource<TransactionStatus> resource) {
        Handle h = cast(resource);
        if (h.aborted.compareAndSet(false, true)) {
            h.held.abort();
        }
    }

    private Handle owned(DBManagedResource<TransactionStatus> resource, String action) {
        Handle h = cast(resource);
        if (Thread.currentThread() != h.owner) {
            throw new IllegalStateException("A Spring transaction is bound to the job's thread: " + action + " on datasource '" + name
                    + "' must run on " + h.owner + ", not " + Thread.currentThread());
        }
        return h;
    }

    private Handle cast(DBManagedResource<TransactionStatus> resource) {
        if (!(resource instanceof Handle h) || h.provider() != this) {
            throw new IllegalArgumentException("Handle was not produced by the Spring transaction provider '" + name + "': " + resource);
        }
        return h;
    }

    private final class Handle implements DBManagedResource<TransactionStatus> {
        private final HeldResource held;
        private final boolean readOnly;
        private final Thread owner;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean aborted = new AtomicBoolean(false);
        private volatile TransactionStatus status;

        private Handle(HeldResource held, boolean readOnly, Thread owner) {
            this.held = held;
            this.readOnly = readOnly;
            this.owner = owner;
        }

        private SpringTransactionResourceProvider provider() {
            return SpringTransactionResourceProvider.this;
        }

        @Override
        public TransactionStatus unwrap() {
            return status;
        }
    }
}
