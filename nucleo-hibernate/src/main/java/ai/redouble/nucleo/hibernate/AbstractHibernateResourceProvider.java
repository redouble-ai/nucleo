/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.hibernate;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import jakarta.transaction.Synchronization;
import org.hibernate.*;
import org.hibernate.resource.jdbc.spi.*;
import org.hibernate.resource.transaction.spi.*;

import java.sql.Connection;
import java.sql.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * A {@link DBResourceProvider} over a Hibernate {@link SessionFactory}: each handle owns one
 * {@link Session} holding one connection from admission to completion. What a job unwraps is
 * the subclass's choice ({@link #expose}): the session itself for
 * {@link HibernateResourceProvider}, or a host's own wrapper around it.
 *
 * <p>The session opens in {@link PhysicalConnectionHandlingMode#DELAYED_ACQUISITION_AND_HOLD}
 * and {@link #acquire} forces the connection out of the pool at once. Hibernate's default for a
 * resource-local session hands the connection back after every transaction, which would send
 * a job holding its admission permit back to the pool at each commit. The mode is a property
 * of the session, so the host's factory and every session other code opens from it stay as
 * they are. The pool behind the factory is the host's and is never read or tuned here; the
 * admission bound {@code maxConcurrent} must not exceed the connections it reserves for jobs.
 *
 * <p>{@link #begin} registers a transaction synchronization whose {@code afterCompletion}
 * releases a latch, and {@link #awaitCompletion} waits on it for at most the completion
 * timeout the host passed. On a synchronous commit the latch is already open when
 * {@link #commit} returns; the wait is there for a stack whose completion is not.
 *
 * @param <T> what a job unwraps from the handle
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public abstract class AbstractHibernateResourceProvider<T> implements DBResourceProvider<T> {

    /** An abort hands the socket teardown to a thread of its own, never to the caller's. */
    private static final Executor ABORT_EXECUTOR = task -> Thread.ofVirtual().name("hibernate-abort").start(task);

    private final String name;
    private final Supplier<SessionFactory> sessionFactory;
    private final DatabaseGate admission;
    private final Duration completionTimeout;

    /**
     * @param name              the datasource's identity: the health row {@code db:<name>} and
     *                          the name jobs look the provider up by
     * @param sessionFactory    the host's factory, whatever pool is behind it, asked for at
     *                          every {@link #acquire}: a host whose factory is built after the
     *                          provider, or rebuilt, is served the one current at the time
     * @param maxConcurrent     how many jobs may hold a session at once
     * @param completionTimeout how long {@link #awaitCompletion} waits before refusing
     */
    protected AbstractHibernateResourceProvider(String name, Supplier<SessionFactory> sessionFactory, int maxConcurrent, Duration completionTimeout) {
        this.name = name;
        this.sessionFactory = sessionFactory;
        this.admission = new DatabaseGate(name, maxConcurrent);
        this.completionTimeout = completionTimeout;
    }

    /**
     * What a job unwraps, built once per handle around its session. Called on the session
     * {@link #acquire} just opened and connected.
     */
    protected abstract T expose(Session session);

    /**
     * Called by {@link #close} on a handle that was not aborted, after its transaction was
     * rolled back and before its session closes: the place for a host's own end-of-work
     * bookkeeping on what it exposed. Does nothing by default.
     */
    protected void closing(T value) {
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RateLimiter<Void> admission() {
        return admission;
    }

    /** The factory the next {@link #acquire} opens its session from. */
    public SessionFactory getSessionFactory() {
        return sessionFactory.get();
    }

    @Override
    public DBManagedResource<T> acquire(boolean readOnly) {
        Session session = sessionFactory.get().withOptions()
                .connectionHandlingMode(PhysicalConnectionHandlingMode.DELAYED_ACQUISITION_AND_HOLD)
                .openSession();
        try {
            session.setDefaultReadOnly(readOnly);
            Handle handle = new Handle(session);
            session.doWork(connection -> {
                handle.connection = connection;
                handle.originalReadOnly = connection.isReadOnly();
                connection.setReadOnly(readOnly);
            });
            handle.value = expose(session);
            return handle;
        }
        catch (RuntimeException e) {
            UncorrectableRuntimeLLMException failure = new UncorrectableRuntimeLLMException(
                    "Could not open a connected session on datasource '" + name + "': " + e.getMessage(), e);
            try {
                session.close();
            }
            catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public void begin(DBManagedResource<T> resource) {
        Handle h = cast(resource);
        if (h.closed.get()) {
            throw new UncorrectableRuntimeLLMException("Cannot begin a transaction on a closed handle of datasource '" + name + "'");
        }
        try {
            Transaction transaction = h.session.beginTransaction();
            CountDownLatch latch = new CountDownLatch(1);
            transaction.registerSynchronization(new LatchSynchronization(latch));
            h.latch = latch;
            h.transaction = transaction;
        }
        catch (RuntimeException e) {
            throw new UncorrectableRuntimeLLMException("Could not begin a transaction on datasource '" + name + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void commit(DBManagedResource<T> resource) {
        Handle h = cast(resource);
        Transaction transaction = h.transaction;
        if (transaction == null || !transaction.isActive()) {
            return;
        }
        try {
            transaction.commit();
        }
        catch (RuntimeException e) {
            // a transaction that failed before afterCompletion leaves the latch shut
            release(h);
            throw new UncorrectableRuntimeLLMException("Commit failed on datasource '" + name + "': " + e.getMessage(), e);
        }
    }

    /**
     * Waits for the committed transaction's completion, for at most the completion timeout. A
     * transaction that reached a terminal state without its synchronization firing is
     * complete; one that did not within the timeout is refused.
     */
    @Override
    public void awaitCompletion(DBManagedResource<T> resource) {
        Handle h = cast(resource);
        CountDownLatch latch = h.latch;
        if (latch == null) {
            return;
        }
        try {
            if (latch.await(completionTimeout.toMillis(), TimeUnit.MILLISECONDS) || terminal(h.transaction)) {
                return;
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncorrectableRuntimeLLMException("Interrupted while awaiting commit completion on datasource '" + name + "'", e);
        }
        throw new UncorrectableRuntimeLLMException("Commit completion on datasource '" + name + "' did not arrive within " + completionTimeout);
    }

    /** Idempotent: nothing to do without an active transaction, or after an abort. */
    @Override
    public void rollback(DBManagedResource<T> resource) {
        Handle h = cast(resource);
        try {
            Transaction transaction = h.transaction;
            if (!h.aborted.get() && transaction != null && transaction.isActive()) {
                transaction.rollback();
            }
        }
        catch (RuntimeException e) {
            throw new UncorrectableRuntimeLLMException("Rollback failed on datasource '" + name + "': " + e.getMessage(), e);
        }
        finally {
            release(h);
        }
    }

    /**
     * Rolls back an active transaction, restores the connection's read-only state, runs
     * {@link #closing}, and closes the session, which hands the connection back to the pool.
     * The session is closed even when any of those fails; the failure is thrown afterwards.
     * After an abort only the session is closed: there is no connection left to roll back or
     * restore, and a pool that fails to reset the severed connection is reported as that
     * failure.
     */
    @Override
    public void close(DBManagedResource<T> resource) {
        Handle h = cast(resource);
        if (!h.closed.compareAndSet(false, true)) {
            return;
        }
        release(h);
        UncorrectableRuntimeLLMException failure = null;
        if (!h.aborted.get()) {
            try {
                Transaction transaction = h.transaction;
                if (transaction != null && transaction.isActive()) {
                    transaction.rollback();
                }
                h.connection.setReadOnly(h.originalReadOnly);
                closing(h.value);
            }
            catch (RuntimeException | SQLException e) {
                failure = new UncorrectableRuntimeLLMException("Could not reset a session of datasource '" + name + "' before closing it: " + e.getMessage(), e);
            }
        }
        try {
            h.session.close();
        }
        catch (RuntimeException e) {
            if (failure == null) {
                failure = new UncorrectableRuntimeLLMException("Could not close a session of datasource '" + name + "': " + e.getMessage(), e);
            }
            else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Aborts the connection the session holds through {@link Connection#abort}, on a thread of
     * its own; the session itself is not touched, since the job's thread may still be inside it.
     */
    @Override
    public void abort(DBManagedResource<T> resource) {
        Handle h = cast(resource);
        if (!h.aborted.compareAndSet(false, true)) {
            return;
        }
        release(h);
        try {
            h.connection.abort(ABORT_EXECUTOR);
        }
        catch (SQLException e) {
            throw new UncorrectableRuntimeLLMException("Could not abort a connection of datasource '" + name + "': " + e.getMessage(), e);
        }
    }

    /** The session behind a handle this provider made, for a subclass that needs it again. */
    protected Session session(DBManagedResource<T> resource) {
        return cast(resource).session;
    }

    private static void release(AbstractHibernateResourceProvider<?>.Handle h) {
        CountDownLatch latch = h.latch;
        if (latch != null) {
            latch.countDown();
        }
    }

    private static boolean terminal(Transaction transaction) {
        return transaction.getStatus().isOneOf(TransactionStatus.COMMITTED, TransactionStatus.NOT_ACTIVE, TransactionStatus.ROLLED_BACK,
                TransactionStatus.FAILED_COMMIT, TransactionStatus.FAILED_ROLLBACK);
    }

    private Handle cast(DBManagedResource<T> resource) {
        if (!(resource instanceof AbstractHibernateResourceProvider<?>.Handle h) || h.provider() != this) {
            throw new IllegalArgumentException("Handle was not produced by the Hibernate provider '" + name + "': " + resource);
        }
        @SuppressWarnings("unchecked")
        Handle own = (Handle) h;
        return own;
    }

    private final class Handle implements DBManagedResource<T> {
        private final Session session;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean aborted = new AtomicBoolean(false);
        private volatile Connection connection;
        private volatile boolean originalReadOnly;
        private volatile T value;
        private volatile Transaction transaction;
        private volatile CountDownLatch latch;

        private Handle(Session session) {
            this.session = session;
        }

        private AbstractHibernateResourceProvider<T> provider() {
            return AbstractHibernateResourceProvider.this;
        }

        @Override
        public T unwrap() {
            return value;
        }
    }

    private static final class LatchSynchronization implements Synchronization {
        private final CountDownLatch latch;

        private LatchSynchronization(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void beforeCompletion() {
        }

        @Override
        public void afterCompletion(int status) {
            latch.countDown();
        }
    }
}
