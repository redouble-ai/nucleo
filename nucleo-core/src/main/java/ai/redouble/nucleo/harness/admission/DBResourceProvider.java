/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;

/**
 * Generic contract for a database resource provider that Nucleo manages
 * on behalf of jobs. Implementations adapt any concrete database stack
 * (Hibernate, plain JDBC, Spring JPA, ...) to Nucleo's lifecycle.
 *
 * <p>A provider is a long-lived object registered at startup. Jobs declare
 * which providers they need via {@link JobRequirements#addProvider};
 * Nucleo then calls {@link #acquire} once per job to obtain a
 * {@link DBManagedResource} handle and drives its lifecycle through the
 * other methods on this interface. The handle is opaque to Nucleo;
 * {@link DBManagedResource#unwrap} lets the job get back the stack-specific
 * object it actually needs.
 *
 * <p>Admission is Nucleo's: the provider exposes its bound as an account through
 * {@link #admission()}, {@link Admission} takes one permit on it as part of the job's whole
 * demand, and only then does Nucleo call {@link #acquire} to materialize the handle under
 * that permit. The provider never gates inside {@link #acquire}; the permit returns when the
 * job's grant is released at close.
 *
 * <p>Every method except {@link #acquire} and {@link #admission} takes a handle that was
 * produced by this provider's own {@link #acquire}. Mixing handles between providers is an
 * implementation error.
 *
 * @param <T> the stack-specific type that jobs unwrap from the handle
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-19)
 */
public interface DBResourceProvider<T> {

    /**
     * Stable identity of the datasource this provider fronts - typically the
     * connection pool name. Used to attribute resource-wait metrics and to
     * label the provider's admission limiter in the system health snapshot, so
     * it should be human-meaningful and unique per datasource. Must not change
     * over the provider's lifetime.
     *
     * @return the datasource identity, never null
     */
    String name();

    /**
     * The admission account for this provider's bound (its connection budget). One permit is
     * one held connection for the life of a handle. Stable over the provider's lifetime.
     */
    RateLimiter<Void> admission();

    /**
     * Materializes a new handle under a permit the caller already holds on
     * {@link #admission()}: checks out the connection or session and warms it, so
     * execution never re-enters the pool. Never gates. Implementations translate
     * checkout failures into {@link UncorrectableRuntimeLLMException}.
     *
     * @param readOnly hint that the job will only read; providers may use
     *                 this to pick a read-only session or connection
     * @return a fresh handle, not shared with any other job
     */
    DBManagedResource<T> acquire(boolean readOnly);

    /**
     * Starts a transaction on the handle. For providers where transactions
     * begin implicitly on first use, this is a no-op.
     *
     * <p>Called by {@link JobResources#beginAll} or directly by jobs that
     * manage chunked commits.
     */
    void begin(DBManagedResource<T> resource);

    /**
     * Commits the transaction on the handle. Synchronous: by the time
     * this returns, the underlying driver has acknowledged the commit.
     * Actual visibility to other connections is a separate concern
     * handled by {@link #awaitCompletion}.
     */
    void commit(DBManagedResource<T> resource);

    /**
     * Blocks until the commit issued for this handle is durably visible
     * to subsequent reads from other connections.
     *
     * <p>Implementations that can prove this is automatic (synchronous
     * commit + no replication lag) may implement as a no-op, but must
     * document that fact. Implementations that cannot prove visibility
     * must block until they can, or throw
     * {@link UncorrectableRuntimeLLMException} if the provider's
     * configured timeout elapses first.
     *
     * <p>Callers that close or abort the handle during an in-flight
     * awaitCompletion MUST see the await return promptly; see
     * {@link #close} and {@link #abort}.
     */
    void awaitCompletion(DBManagedResource<T> resource);

    /**
     * Rolls back the transaction on the handle. Idempotent: safe to call
     * on a handle with no active transaction.
     */
    void rollback(DBManagedResource<T> resource);

    /**
     * Cooperatively closes the handle: flushes any pending state, rolls
     * back any active transaction, and returns the underlying connection or
     * session to the pool. The admission permit is not the provider's to return;
     * {@link Admission} gives it back when the job's grant is released.
     *
     * <p>MUST unblock any thread currently parked in
     * {@link #awaitCompletion} on this handle.
     *
     * <p>Idempotent: repeated close is a no-op after the first successful
     * close.
     */
    void close(DBManagedResource<T> resource);

    /**
     * Aggressively aborts the handle when cooperative close has failed or
     * is not safe. Implementations abort the underlying connection via
     * an executor so the call itself does not block on in-flight driver
     * work.
     *
     * <p>MUST unblock any thread currently parked in
     * {@link #awaitCompletion} on this handle.
     *
     * <p>Idempotent and non-blocking: repeated abort is a no-op after the
     * first successful abort.
     */
    void abort(DBManagedResource<T> resource);
}
