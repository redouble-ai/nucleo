/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.http.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.slf4j.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Per-job container for every resource a job holds during execution: the {@link Grant} that
 * {@link Admission} took for the job's whole {@link Demand} (model token reservations, the HTTP
 * connection permit, custom limiters, database gate permits), the database handles
 * materialized under those permits, and the LLM, embeddings and decision clients.
 *
 * <p>Construction is the job's admission. The demand is assembled from the priced
 * requirements; {@link Admission} grants it whole or parks the thread holding nothing, with the
 * memory gate consulted as the first account, so a job never waits for memory anywhere but in
 * admission; then each declared
 * {@link DBResourceProvider} materializes its handle under the gate permit the grant already
 * holds. A materialization failure closes the handles taken so far and rolls the grant back.
 *
 * <p>Database handles are held in a {@link LinkedHashMap} fixed at construction, so commit
 * order is deterministic (insertion order = declaration order on the job) and a
 * {@link #forceClose()} from the timeout executor can run while the job thread is inside
 * {@link #commitAll()} without either iteration seeing a mutation. Jobs that commit
 * mid-execute can call {@link #beginAll()}, {@link #commitAll()}, and {@link #rollbackAll()}
 * directly; otherwise the dispatcher drives the transaction lifecycle when
 * {@code requiresTransaction} is set.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-08-12)
 */
public class JobResources {
    private static final Logger log = LoggerFactory.getLogger(JobResources.class);

    /**
     * Database handles keyed by the provider that produced them. Insertion
     * order matters: {@link #commitAll} commits in insertion order, and
     * rollback-on-failure runs in reverse order. Never mutated after construction.
     */
    private final Map<DBResourceProvider<?>, DBManagedResource<?>> resources;

    /**
     * Providers whose handle has already been committed in the current
     * transaction cycle. Cleared on every {@link #beginAll}. Used by
     * {@link #commitAll} to build the "uncommitted remainder" set when a
     * commit mid-sequence fails.
     */
    private final Set<DBResourceProvider<?>> committed = new HashSet<>();

    /**
     * LLM clients by model.
     * Created lazily on first request.
     */
    private final Map<ModelSpec, LLMClient> llmClients = new ConcurrentHashMap<>();

    /**
     * Embeddings clients by model.
     * Created lazily on first request.
     */
    private final Map<ModelSpec, EmbeddingsClient> embeddingsClients = new ConcurrentHashMap<>();

    /**
     * Decision clients by model.
     * Created lazily on first request.
     */
    private final Map<ModelSpec, DecisionClient> decisionClients = new ConcurrentHashMap<>();

    /**
     * The job context for the current execution.
     * Used to provide job information to wrapped clients.
     */
    private final JobContext<?> jobContext;

    private final Admission admission;

    /** Everything admission took for this job. Settled exactly once, by close or force-close. */
    private final Grant grant;

    /**
     * Admits the job and materializes its handles. The thread parks in admission until the
     * whole demand fits, holding nothing; an interrupt while parked withdraws the waiter and
     * is thrown as an {@link UncorrectableRuntimeLLMException} with the interrupt as cause and
     * the thread's interrupt flag restored. A provider that fails to acquire rolls the grant
     * back, and the job never runs.
     *
     * @param requirements the attempt's requirements capture, with its model bindings
     *                     already resolved and priced by the dispatcher (null for
     *                     orchestrators and other resource-free jobs)
     * @param jobContext   the job context for the current execution
     * @param admission    the dispatcher's admission monitor
     */
    public JobResources(JobRequirements requirements, JobContext<?> jobContext, Admission admission) {
        this.jobContext = jobContext;
        this.admission = admission;
        List<DBResourceProvider<?>> providers = requirements != null ? requirements.getProviders() : List.of();
        boolean readOnly = requirements != null && requirements.isReadOnly();
        long entered = System.nanoTime();
        Grant granted;
        try {
            granted = admission.admit(demandOf(requirements), jobContext);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncorrectableRuntimeLLMException("Interrupted while waiting for admission", e);
        }
        granted.getWaitTimesMs().forEach(jobContext::recordResourceWait);
        jobContext.recordAdmissionWait((System.nanoTime() - entered) / 1_000_000L);
        LinkedHashMap<DBResourceProvider<?>, DBManagedResource<?>> handles = new LinkedHashMap<>();
        try {
            for (DBResourceProvider<?> provider : providers) {
                DBManagedResource<?> handle;
                try {
                    handle = provider.acquire(readOnly);
                }
                catch (Throwable t) {
                    throw new UncorrectableRuntimeLLMException(
                            "Failed to acquire database handle from " + provider.getClass().getSimpleName() + ": " + t.getMessage(), t);
                }
                handles.put(provider, handle);
            }
        }
        catch (Throwable t) {
            log.error("Materialization failed for job {}, rolling back {} handles and the grant", jobContext.getJobId(), handles.size());
            List<Map.Entry<DBResourceProvider<?>, DBManagedResource<?>>> taken = new ArrayList<>(handles.entrySet());
            Collections.reverse(taken);
            for (Map.Entry<DBResourceProvider<?>, DBManagedResource<?>> entry : taken) {
                closeHandleQuietly(entry.getKey(), entry.getValue());
            }
            admission.rollback(granted);
            throw t;
        }
        this.resources = Collections.unmodifiableMap(handles);
        this.grant = granted;
        // Mark resources as acquired (sets ThreadLocal for deadlock prevention)
        jobContext.markResourcesAcquired();
    }

    /**
     * The job's whole demand, assembled from its priced requirements in declaration order:
     * one account per resolved binding, the shared HTTP connection gate when the job makes
     * HTTP calls, each custom limiter with its amount, and each declared provider's admission
     * gate. A binding's account is what its entry declares: a token reservation on the
     * entry's bucket when the entry is bounded by a quota window ({@code tpm}), one permit on
     * the entry's gate when it is bounded by how many requests its server takes at once
     * ({@code max_concurrent}). {@link Demand} normalizes entries that debit the same
     * account. Null requirements (orchestrators) yield the empty demand.
     */
    static Demand demandOf(JobRequirements requirements) {
        Demand demand = new Demand();
        if (requirements == null) {
            return demand;
        }
        for (ModelBinding binding : requirements.getModelBindings()) {
            ModelSpec model = binding.getModel();
            if (model.getMaxConcurrent() != null) {
                demand.add(RateLimiterRegistry.getInstance().gate(model), null);
            }
            else {
                demand.add(RateLimiterRegistry.getInstance().getRateLimiter(model), binding.getReservation());
            }
        }
        if (requirements.requiresHttpConnection()) {
            demand.add(HttpConnectionPools.getInstance().gate(), null);
        }
        for (Map.Entry<RateLimiter<?>, Object> custom : requirements.getCustomRateLimiters().entrySet()) {
            addCustom(demand, custom.getKey(), custom.getValue());
        }
        for (DBResourceProvider<?> provider : requirements.getProviders()) {
            demand.add(provider.admission(), null);
        }
        return demand;
    }

    @SuppressWarnings("unchecked")
    private static <T> void addCustom(Demand demand, RateLimiter<T> limiter, Object amount) {
        demand.add(limiter, (T) amount);
    }

    /**
     * Gets an LLM client for the specified model.
     * The client handles its own rate limiting internally.
     * Wraps the client to capture all responses for observability.
     *
     * @param model the model to use
     * @return the LLM client (wrapped to capture responses)
     */
    public LLMClient getLLMClient(ModelSpec model) {
        return llmClients.computeIfAbsent(model, m ->
                // Wrap the app-shared client to capture responses and provide job context
                new ObservableLLMClient(ClientProviders.llmClient(m), jobContext));
    }

    /**
     * Gets an embeddings client for the specified model.
     * The client handles its own rate limiting internally.
     * Wraps the client to capture every call for observability, as the LLM client is.
     *
     * @param model the embedding model to use
     * @return the embeddings client (wrapped to capture responses)
     * @throws UnsupportedOperationException if model doesn't support embeddings
     */
    public EmbeddingsClient getEmbeddingsClient(ModelSpec model) {
        return embeddingsClients.computeIfAbsent(model, m ->
                new ObservableEmbeddingsClient(ClientProviders.embeddingsClient(m), jobContext));
    }

    /**
     * Gets a decision client for the specified model, wrapped to record every call on the
     * job as the LLM and embeddings clients are.
     *
     * @param model the decision model to use
     * @return the decision client (wrapped to capture responses)
     */
    public DecisionClient getDecisionClient(ModelSpec model) {
        return decisionClients.computeIfAbsent(model, m ->
                new ObservableDecisionClient(ClientProviders.decisionClient(m), jobContext));
    }

    /**
     * Unwraps the stack-specific object for the given provider. The provider
     * must have been declared on the job's requirements; otherwise throws
     * {@link IllegalStateException}.
     */
    public <T> T get(DBResourceProvider<T> provider) {
        DBManagedResource<?> handle = resources.get(provider);
        if (handle == null) {
            throw new IllegalStateException("Provider not declared on job requirements: " + provider.getClass().getName());
        }
        @SuppressWarnings("unchecked")
        T unwrapped = (T)handle.unwrap();
        return unwrapped;
    }

    /**
     * Returns the raw handle for the given provider. Used when callers need
     * provider-specific state on the handle (e.g. a data-layer bridge exposing
     * its own session object through the handle type).
     */
    public <T> DBManagedResource<T> getHandle(DBResourceProvider<T> provider) {
        DBManagedResource<?> handle = resources.get(provider);
        if (handle == null) {
            throw new IllegalStateException("Provider not declared on job requirements: " + provider.getClass().getName());
        }
        @SuppressWarnings("unchecked")
        DBManagedResource<T> typed = (DBManagedResource<T>)handle;
        return typed;
    }

    /**
     * Begins a transaction on every acquired provider. Clears any prior
     * commit state so the same JobResources can drive multiple transaction
     * cycles (chunked-commit pattern).
     */
    public void beginAll() {
        committed.clear();
        for (Map.Entry<DBResourceProvider<?>, DBManagedResource<?>> entry : resources.entrySet()) {
            beginOne(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Commits every active transaction in insertion order, waiting for
     * visibility after each commit. If a commit or awaitCompletion fails,
     * the remaining providers (those not yet committed) are rolled back in
     * reverse insertion order and the original failure is thrown with every
     * rollback exception attached via {@link Throwable#addSuppressed}.
     *
     * <p>Providers that already committed successfully cannot be rolled back
     * (that's the nature of commit); callers observing a failure here must
     * treat the earlier providers' data as durable and only the remainder
     * as rolled back.
     */
    public void commitAll() {
        Throwable firstFailure = null;
        DBResourceProvider<?> failureProvider = null;
        List<Throwable> suppressed = new ArrayList<>();

        for (Map.Entry<DBResourceProvider<?>, DBManagedResource<?>> entry : resources.entrySet()) {
            DBResourceProvider<?> provider = entry.getKey();
            DBManagedResource<?> handle = entry.getValue();
            if (committed.contains(provider)) {
                continue;  // already committed in an earlier partial cycle
            }
            try {
                commitOne(provider, handle);
                awaitOne(provider, handle);
                committed.add(provider);
            }
            catch (Throwable t) {
                firstFailure = t;
                failureProvider = provider;
                break;
            }
        }

        if (firstFailure == null) {
            return;
        }

        // Roll back the providers we have not yet committed, in reverse
        // insertion order. The failing provider's own state is undefined
        // (commit partially applied), so try rollback on it too as a
        // best-effort cleanup.
        List<DBResourceProvider<?>> toRollback = new ArrayList<>();
        for (DBResourceProvider<?> provider : resources.keySet()) {
            if (!committed.contains(provider)) {
                toRollback.add(provider);
            }
        }
        Collections.reverse(toRollback);
        for (DBResourceProvider<?> provider : toRollback) {
            try {
                rollbackOne(provider, resources.get(provider));
            }
            catch (Throwable rollbackEx) {
                suppressed.add(rollbackEx);
            }
        }

        RuntimeException wrapper = new UncorrectableRuntimeLLMException(
                "commitAll failed on " + failureProvider.getClass().getSimpleName() + ": " + firstFailure.getMessage(), firstFailure);
        for (Throwable s : suppressed) {
            wrapper.addSuppressed(s);
        }
        throw wrapper;
    }

    /**
     * Rolls back every active transaction. Called by the dispatcher when a
     * job throws, or directly by chunked-commit jobs that decide mid-execute
     * to abandon the current cycle. Idempotent: providers already committed
     * are skipped.
     */
    public void rollbackAll() {
        List<Throwable> suppressed = new ArrayList<>();
        List<DBResourceProvider<?>> providers = new ArrayList<>(resources.keySet());
        Collections.reverse(providers);
        for (DBResourceProvider<?> provider : providers) {
            if (committed.contains(provider)) {
                continue;
            }
            try {
                rollbackOne(provider, resources.get(provider));
            }
            catch (Throwable t) {
                suppressed.add(t);
            }
        }
        if (!suppressed.isEmpty()) {
            RuntimeException wrapper = new UncorrectableRuntimeLLMException("rollbackAll encountered " + suppressed.size() + " errors");
            for (Throwable s : suppressed) {
                wrapper.addSuppressed(s);
            }
            throw wrapper;
        }
    }

    /**
     * Checks if this bundle has any database providers.
     */
    public boolean hasDatabase() {
        return !resources.isEmpty();
    }

    /**
     * Closes database handles, then releases the grant: connections go back to the pool
     * before the permits that represent them return. Every handle is closed even if one
     * fails, and the grant is released regardless. LLM clients are shared across jobs via
     * the Models facade and closed during shutdown.
     */
    public void close() throws IOException {
        // Mark resources as released FIRST (clears ThreadLocal for deadlock prevention)
        jobContext.markResourcesReleased();
        IOException closeFailure = null;
        try {
            for (Map.Entry<DBResourceProvider<?>, DBManagedResource<?>> entry : resources.entrySet()) {
                try {
                    closeOne(entry.getKey(), entry.getValue());
                }
                catch (Throwable t) {
                    if (closeFailure == null) {
                        closeFailure = new IOException("Failed to close database handle from " + entry.getKey().getClass().getSimpleName(), t);
                    }
                    else {
                        closeFailure.addSuppressed(t);
                    }
                }
            }
        }
        finally {
            admission.release(grant);
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    /**
     * Forcibly terminates resources during aggressive timeout enforcement: aborts and closes
     * every database handle through its provider, releases the grant, and clears LLM clients.
     * Safe after and during {@link #close()}: the handle container never changes, handles
     * carry their own closed and aborted flags, and the grant settles once.
     */
    public void forceClose() {
        log.warn("Force closing resources for job {}", jobContext.getJobId());
        jobContext.markResourcesReleased();
        try {
            for (Map.Entry<DBResourceProvider<?>, DBManagedResource<?>> entry : resources.entrySet()) {
                try {
                    abortOne(entry.getKey(), entry.getValue());
                    closeOne(entry.getKey(), entry.getValue());
                }
                catch (Throwable t) {
                    log.error("Error during forced close of handle from {}: {}", entry.getKey().getClass().getSimpleName(), t.getMessage());
                }
            }
        }
        finally {
            admission.release(grant);
        }
        llmClients.clear();
    }

    /**
     * Gets the HTTP client for making external API requests.
     * Returns the shared client from HttpConnectionPools.
     *
     * @return HTTP client from shared pool
     */
    public CloseableHttpClient getHttpClient() {
        return HttpConnectionPools.getInstance().getClient();
    }

    // Internal helpers that bridge the wildcard types in the resources map
    // to the parameterized DBResourceProvider<T> calls.

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void beginOne(DBResourceProvider<?> provider, DBManagedResource<?> handle) {
        ((DBResourceProvider)provider).begin(handle);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void commitOne(DBResourceProvider<?> provider, DBManagedResource<?> handle) {
        ((DBResourceProvider)provider).commit(handle);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void awaitOne(DBResourceProvider<?> provider, DBManagedResource<?> handle) {
        ((DBResourceProvider)provider).awaitCompletion(handle);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void rollbackOne(DBResourceProvider<?> provider, DBManagedResource<?> handle) {
        ((DBResourceProvider)provider).rollback(handle);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void closeOne(DBResourceProvider<?> provider, DBManagedResource<?> handle) {
        ((DBResourceProvider)provider).close(handle);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void abortOne(DBResourceProvider<?> provider, DBManagedResource<?> handle) {
        ((DBResourceProvider)provider).abort(handle);
    }

    private static void closeHandleQuietly(DBResourceProvider<?> provider, DBManagedResource<?> handle) {
        try {
            closeOne(provider, handle);
        }
        catch (Throwable t) {
            log.error("Failed to close handle from {} during rollback: {}", provider.getClass().getSimpleName(), t.getMessage());
        }
    }
}
