/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * Manages conversation ownership and access control in a multithreaded environment.
 *
 * <p>Key features:
 * <ul>
 *   <li>Exclusive ownership model - one job owns one conversation at a time</li>
 *   <li>Automatic cleanup when jobs complete/fail/timeout</li>
 *   <li>Force-release mechanism for user intervention</li>
 * </ul>
 *
 * <p>Thread safety: all public methods are thread-safe. Each conversation's ownership
 * lives in its own {@code ConversationHolder}, guarded by a per-holder lock whose
 * condition parks a waiting turn until the owner releases.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-14)
 */
public class ConversationService extends AbstractStoppable {
    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final ConversationService INSTANCE = new ConversationService();
    private final Map<String, ConversationHolder> conversations = new ConcurrentHashMap<>();
    private SessionStore sessionStore;
    private PersistentStore persistentStore;
    private final ScheduledExecutorService cleanupExecutor = Executors.newScheduledThreadPool(1);
    /** How long an acquisition waits for the current owner; package-private so tests can exercise the timeout without a 30s park. */
    static long conversationTimeoutMs = 30000;

    /**
     * Per-conversation holder for state and ownership. Ownership transfers under the
     * holder's own lock: an acquiring turn parks on the {@code handedOff} condition until
     * the owner releases, so a busy conversation is waited for, never spun on.
     * Expiry is dynamic: set explicitly via {@link #setExpiry(Duration)} when a thinker
     * releases the conversation. Until set, the holder does not expire (Long.MAX_VALUE).
     */
    private static class ConversationHolder {
        final ConversationContext context;
        final String userId;  // Owner of the conversation (not the job)
        private final ReentrantLock lock = new ReentrantLock();
        /** Signalled by every release, so a turn waiting on a busy conversation parks until handed off. */
        private final Condition handedOff = lock.newCondition();
        /** The owning job. Written only under {@link #lock}; readers need none. */
        private volatile String owner;
        private volatile long expiryTime = Long.MAX_VALUE;
        // The live thinker driving the owning turn. One rule: the reference dies with
        // holder ownership - set at acquisition, cleared by the release that actually
        // transfers ownership away. A reader holding a stale reference is harmless:
        // a closed turn refuses messages, and the caller falls back to a new turn.
        private volatile Thinker<?, ?> owningThinker;
        // Whether the durable record was already created for this conversation. Only
        // consulted for a MINTED conversation's first save; adopted conversations have
        // their record by definition.
        private volatile boolean recordCreated;

        ConversationHolder(ConversationContext context, String userId) {
            this.context = context;
            this.userId = userId;
        }

        /**
         * Takes ownership: at once when the conversation is free or already this job's,
         * otherwise after the owner releases it, up to the timeout.
         *
         * @return false when the timeout passed with the conversation still owned by another job
         */
        boolean acquire(String jobId, long timeoutMs) throws InterruptedException {
            lock.lock();
            try {
                long left = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                while (owner != null && !owner.equals(jobId)) {
                    if (left <= 0) {
                        return false;
                    }
                    left = handedOff.awaitNanos(left);
                }
                owner = jobId;
                // Reset expiry on re-acquisition (conversation is active again)
                expiryTime = Long.MAX_VALUE;
                return true;
            }
            finally {
                lock.unlock();
            }
        }

        /**
         * Release ownership if owned by the given job, waking whoever waits for it.
         */
        void release(String jobId) {
            lock.lock();
            try {
                if (jobId.equals(owner)) {
                    owner = null;
                    owningThinker = null;
                    handedOff.signalAll();
                }
            }
            finally {
                lock.unlock();
            }
        }

        /**
         * Get current owner (may be null).
         */
        String getOwner() {
            return owner;
        }

        /**
         * Sets when this holder should expire, relative to now.
         *
         * @param retainDuration how long to keep the conversation in memory after release
         */
        void setExpiry(Duration retainDuration) {
            this.expiryTime = System.currentTimeMillis() + retainDuration.toMillis();
        }

        /**
         * Check if this holder can be evicted (expired and unowned).
         */
        boolean isExpired() {
            return System.currentTimeMillis() > expiryTime && owner == null;
        }
    }

    private ConversationService() {
        JobDispatcher.getInstance().registerForShutdown(this);
        // Start periodic cleanup task to prevent memory leaks
        cleanupExecutor.scheduleAtFixedRate(this::sweepExpired, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * One pass of the periodic cleanup: every expired, unowned holder is evicted from
     * memory (its state was saved at the last boundary; eviction never persists).
     * Package-private so tests can run a sweep without waiting on the 5-minute schedule.
     */
    void sweepExpired() {
        try {
            int removed = 0;
            for (Map.Entry<String, ConversationHolder> entry : conversations.entrySet()) {
                if (entry.getValue().isExpired()) {
                    conversations.remove(entry.getKey(), entry.getValue());
                    removed++;
                }
            }
            GateStats stats = getStats();
            if (removed > 0) {
                log.info("[ConvSvc] Cleanup: removed {} expired | {}", removed, stats);
            } else if (stats.total() > 0) {
                log.debug("[ConvSvc] Stats: {}", stats);
            }
        }
        catch (Exception e) {
            log.error("Error during conversation cleanup", e);
        }
    }

    @Override
    protected void doStop() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        }
        catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        conversations.clear();
    }

    public static ConversationService getInstance() {
        return INSTANCE;
    }

    /**
     * Sets the session store for HTTP session-based persistence.
     *
     * @param sessionStore the session store implementation
     */
    public void setSessionStore(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /**
     * Sets the persistent store for database-based persistence.
     *
     * @param persistentStore the persistent store implementation
     */
    public void setPersistentStore(PersistentStore persistentStore) {
        this.persistentStore = persistentStore;
    }

    /**
     * The thinker's declarations onto its conversation, at every point a conversation is
     * created or rehydrated for it: the grade (which model), the depth (how much effort) and
     * the output declaration (how much answer). Stamped here, once, so nothing downstream
     * ever finds a conversation that does not say what its seat asked for - the compaction
     * fit check and the first call read them before any per-call code runs. A rehydrated
     * conversation takes the adopting thinker's CURRENT declarations, not the ones it was
     * stored with.
     */
    private static void stampDeclarations(ConversationContext context, Thinker<?, ?> thinker) {
        context.setGrade(thinker.getGrade());
        context.setDepth(thinker.getDepth());
        context.setOutputDeclaration(thinker.getOutputDeclaration());
        context.setInteractive(thinker.isInteractive());
    }

    /**
     * Single entry point for obtaining a conversation.
     * Returns a future that completes when the conversation is available.
     * Never throws for ownership conflicts - the future's thread parks on the holder's
     * condition and completes when the current owner releases, up to the timeout.
     *
     * @param conversationId  the conversation ID the thinker declared - minted, adopted,
     *                        or the temporary jobId-keyed default
     * @param thinker         the thinker requesting the conversation
     * @param responseHandler the response handler for message rehydration
     * @param mustExist       true for an adopt/resume: the id must resolve from memory or a
     *                        store, and the future fails instead of silently minting an empty
     *                        conversation; false creates fresh when nothing is found
     * @return future that completes with fully initialized conversation context
     */
    public CompletableFuture<ConversationContext> obtainConversation(String conversationId, Thinker<?, ?> thinker, ResponseHandler<?> responseHandler, boolean mustExist) {
        if (isStopping()) {
            log.error("[ConvSvc] Shutting down, dropping request for {}", conversationId);
            return CompletableFuture.failedFuture(new IllegalStateException("ConversationService is shutting down"));
        }
        String userId = thinker.getUserId();
        String jobId = thinker.getJobId();
        // Distinguishes "the id resolved to an existing conversation" from "nothing was found
        // and one was minted". Without it a resume that misses is indistinguishable from a
        // fresh start, and the caller silently reasons over an empty history.
        boolean[] mintedFresh = {false};

        // Get or create the conversation holder
        ConversationHolder holder = conversations.computeIfAbsent(conversationId, id -> {
            // Try to load from persistence first
            ConversationPersistenceSnapshot snapshot = null;

            // The jobId-keyed default asks for a FRESH temporary conversation. It is
            // never persisted (saveConversation skips temporary contexts), so a store
            // consult can only miss - and on a durable store each miss is a dispatched
            // read. Lanes run thousands of thinkers, so this test is the difference
            // between zero reads and one guaranteed-miss read per thinker.
            boolean temporaryDefault = id.equals(jobId);

            // Try session store first
            if (!temporaryDefault && sessionStore != null) {
                snapshot = sessionStore.load(id, userId, (Identifiable) thinker);
                if (snapshot != null) {
                    log.debug("[ConvSvc] Found {} in session store", id);
                }
            }

            // Try persistent store if not found
            if (!temporaryDefault && snapshot == null && persistentStore != null) {
                snapshot = persistentStore.load(id, userId, (Identifiable) thinker);
                if (snapshot != null) {
                    log.debug("[ConvSvc] Found {} in persistent store", id);
                }
            }

            ConversationContext context;
            if (snapshot != null) {
                // Rehydrate from snapshot - the stored serving spec comes back as the prior;
                // the adopting thinker's CURRENT declaration drives resolution from here
                context = snapshot.toConversation(responseHandler);
                stampDeclarations(context, thinker);
                // Announced tools rode the conversation content through persistence; this
                // declares the thinker's current palette, and only names the conversation
                // has never seen reach the model, as a stream announcement at the next render
                context.addTools(thinker.buildToolDefinitionBlocks());
                log.info("[ConvSvc] Rehydrated {} from persistence", id);
            }
            else {
                // Create brand new conversation carrying the thinker's declaration
                context = new ConversationContext(id);
                context.setUserId(userId);
                stampDeclarations(context, thinker);
                // DO NOT set tools for new conversation - let the Thinker do it
                mintedFresh[0] = true;
                log.debug("[ConvSvc] Created {}", id);
            }

            return new ConversationHolder(context, userId);
        });
        if (mustExist && mintedFresh[0]) {
            conversations.remove(conversationId, holder);
            return CompletableFuture.failedFuture(new SystemException("ConversationService",
                    "Cannot resume conversation " + conversationId + ": it is not in memory and no store holds it. "
                    + "It was most likely evicted after its retention elapsed.", null));
        }

        // Return a future that takes ownership, waiting for the current owner's release up to the timeout
        return CompletableFuture.supplyAsync(() -> {
            String currentOwner = holder.getOwner();
            if (currentOwner != null && !currentOwner.equals(jobId)) {
                log.debug("[ConvSvc] Job {} waiting for {} currently owned by {}", jobId, conversationId, currentOwner);
            }
            try {
                if (!holder.acquire(jobId, conversationTimeoutMs)) {
                    // The same typed refusal the mustExist path fails with: callers see a
                    // SystemException cause either way, never a raw wrapper of a known fault
                    throw new CompletionException(new SystemException("ConversationService",
                            "Timed out after " + conversationTimeoutMs + "ms waiting for conversation " + conversationId + " (owned by " + holder.getOwner() + ")", null));
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(new SystemException("ConversationService",
                        "Interrupted while waiting for conversation " + conversationId, e));
            }
            holder.owningThinker = thinker;
            // The single workflowId stamping point: warm holders, cold restores and
            // fresh creates all pass through acquisition, so the context - and every
            // snapshot written from it - always names the turn currently driving it
            holder.context.setWorkflowId(thinker.getWorkflowId());
            // Set safety-net expiry: if the thinker dies without reaching its finally
            // block, this ensures the conversation gets cleaned up eventually
            Duration safetyTimeout = thinker.getRetainDuration();
            if (safetyTimeout != null && !safetyTimeout.isZero()) {
                holder.setExpiry(safetyTimeout);
            }
            log.debug("[ConvSvc] Job {} acquired {}", jobId, conversationId);
            return holder.context;
        });
    }

    /**
     * Releases ownership of a conversation by a thinker with immediate eviction.
     * The Duration.ZERO shorthand: release and evict immediately.
     *
     * @param thinker the thinker releasing ownership
     */
    public void release(Thinker<?, ?> thinker) {
        release(thinker, Duration.ZERO);
    }

    /**
     * Releases ownership of a conversation with duration-based retention.
     * <ul>
     *   <li>{@code Duration.ZERO}: evict from memory immediately</li>
     *   <li>Positive duration: release ownership, set dynamic expiry</li>
     * </ul>
     * Release does not persist: durability is the owner's job at conversation
     * boundaries ({@link #saveConversation}), where the owner knows what changed.
     *
     * @param thinker        the thinker releasing ownership
     * @param retainDuration how long to keep the conversation in memory after release
     */
    public void release(Thinker<?, ?> thinker, Duration retainDuration) {
        String conversationId = thinker.getConversationId();
        String jobId = thinker.getJobId();
        ConversationHolder holder = conversations.get(conversationId);
        if (holder == null) return;
        // Release ownership
        holder.release(jobId);
        // Duration-based retention
        if (retainDuration.isZero() && holder.getOwner() == null) {
            conversations.remove(conversationId, holder);
            log.debug("[ConvSvc] Evicted {} (retain=ZERO)", conversationId);
        } else if (!retainDuration.isZero()) {
            holder.setExpiry(retainDuration);
            log.debug("[ConvSvc] Released {} with {}s retention", conversationId, retainDuration.toSeconds());
        }
    }

    /**
     * Evicts a conversation from memory. No persistence happens here: eviction only
     * runs on unowned holders, and an unowned conversation was already saved at its
     * last boundary by the owner that released it.
     *
     * @param conversationId the conversation ID
     * @param holder         the conversation holder to evict
     */
    private void evictFromMemory(String conversationId, ConversationHolder holder) {
        conversations.remove(conversationId, holder);
        log.debug("[ConvSvc] Evicted {}", conversationId);
    }

    /**
     * Evicts a conversation from memory by its conversation ID. No-op if the
     * conversation is not in memory or is currently owned. An unowned conversation
     * was saved at its last boundary, so eviction is purely a memory operation.
     *
     * @param conversationId the conversation ID to evict
     */
    public void evictConversation(String conversationId) {
        ConversationHolder holder = conversations.get(conversationId);
        if (holder == null) return;
        if (holder.getOwner() != null) {
            log.warn("[ConvSvc] Cannot evict {} - still owned by {}", conversationId, holder.getOwner());
            return;
        }
        evictFromMemory(conversationId, holder);
    }

    /**
     * The boundary save: fired by the owning thinker on every user message in, every
     * response out, and on an abnormal exchange end. The first save of a MINTED
     * conversation creates its durable record (titled and embedded from the first
     * user message, exactly once); every other save is a plain update. Durability
     * lives here and only here - release and eviction transfer ownership and memory,
     * they do not save.
     *
     * @param context the conversation context to save
     * @param thinker the thinker owning the conversation
     */
    public void saveConversation(ConversationContext context, Thinker<?, ?> thinker) {
        if (context == null || thinker == null) {
            return;
        }
        if (persistentStore == null || context.isTemporary()) {
            return;
        }
        ConversationHolder holder = conversations.get(context.getConversationId());
        if (thinker.isConversationMinted() && holder != null && !holder.recordCreated) {
            holder.recordCreated = true;
            persistentStore.createContext(context, thinker);
        }
        else {
            persistentStore.saveContext(context, thinker);
        }
    }

    /**
     * Force-releases a conversation from its current owner.
     * Used when a user wants to interrupt a stuck job.
     *
     * @param conversationId the conversation ID to force-release
     * @param userId         the user requesting the release (must own the conversation)
     * @return the job ID that was cancelled, or null if no job was owning it
     * @throws SecurityException if user doesn't own the conversation
     */
    public String forceRelease(String conversationId, String userId) {
        ConversationHolder holder = conversations.get(conversationId);
        if (holder == null) {
            throw new IllegalArgumentException("Conversation with ID " + conversationId + " does not exist");
        }

        // Security check
        if (!holder.userId.equals(userId)) {
            throw new SecurityException("User " + userId + " does not own conversation " + conversationId);
        }

        String jobToCancel = holder.getOwner();
        if (jobToCancel != null) {
            Thinker<?, ?> turn = holder.owningThinker;
            if (turn != null) {
                // Cancel the whole turn workflow - the owning thinker and its in-flight
                // child jobs alike. The holder is deliberately NOT released here: the
                // cancelled turn's own finally releases it, so no second turn can acquire
                // the context while the dying one still mutates it.
                log.warn("[ConvSvc] Force-cancelling turn workflow {} over {} (job {}) at user {} request", turn.getWorkflowId(), conversationId, jobToCancel, userId);
                JobDispatcher.getInstance().cancelWorkflow(turn.getWorkflowId(), "Conversation force-released by " + userId);
            }
            else {
                // No live thinker reference - a wedged or foreign owner. Legacy release as
                // last resort so the conversation does not stay locked forever.
                log.warn("[ConvSvc] Force-releasing {} from job {} (no live thinker reference) at user {} request", conversationId, jobToCancel, userId);
                holder.release(jobToCancel);
            }
        }
        return jobToCancel;
    }

    /**
     * The thinker currently driving a turn over this conversation, or null when the
     * conversation is idle, unknown, or owned on behalf of a different user. User-guarded:
     * only the conversation's owner may reach the live thinker.
     *
     * @param conversationId the conversation to look up
     * @param userId         the requesting user; must own the conversation
     * @return the owning thinker, or null
     */
    public Thinker<?, ?> owningThinker(String conversationId, String userId) {
        ConversationHolder holder = conversations.get(conversationId);
        if (holder == null || !holder.userId.equals(userId)) {
            return null;
        }
        return holder.owningThinker;
    }

    /**
     * Releases all conversations owned by a specific job.
     * Safety net called by JobDispatcher when a job completes/fails/times out.
     * Defaults to immediate eviction since the thinker's own release should have
     * already set the appropriate retention.
     *
     * @param jobId the job whose conversations should be released
     */
    public void releaseAllForJob(String jobId) {
        int released = 0;
        for (Map.Entry<String, ConversationHolder> entry : conversations.entrySet()) {
            ConversationHolder holder = entry.getValue();
            if (jobId.equals(holder.getOwner())) {
                holder.release(jobId);
                if (holder.getOwner() == null) {
                    // a job that died still owning its conversation saved at its last
                    // boundary; anything after that boundary died with the job
                    evictFromMemory(entry.getKey(), holder);
                }
                released++;
            }
        }
        if (released > 0) {
            log.info("[ConvSvc] Released and evicted {} conversation(s) from job {}", released, jobId);
        }
    }

    /**
     * Removes a conversation entirely.
     * Can only be done if the conversation is not currently owned.
     *
     * @param conversationId the conversation ID to remove
     * @param userId         the user requesting removal (must own the conversation)
     * @throws IllegalStateException if the conversation is currently owned by a job
     * @throws SecurityException     if user doesn't own the conversation
     */
    public void removeConversation(String conversationId, String userId) {
        ConversationHolder holder = conversations.get(conversationId);
        if (holder == null) {
            return;  // Already gone
        }

        if (!holder.userId.equals(userId)) {
            throw new SecurityException("User " + userId + " does not own conversation " + conversationId);
        }

        String currentOwner = holder.getOwner();
        if (currentOwner != null) {
            throw new IllegalStateException("Cannot remove conversation " + conversationId + " while owned by job " + currentOwner);
        }

        conversations.remove(conversationId);
        // Delete from stores
        if (sessionStore != null) {
            sessionStore.delete(conversationId, userId);
        }
        if (persistentStore != null) {
            persistentStore.delete(conversationId, userId);
        }
        log.info("[ConvSvc] Removed {}, user {}", conversationId, userId);
    }

    // ================ Diagnostics ================

    /**
     * Snapshot of conversation memory stats for diagnostic logging.
     */
    public record GateStats(int total, int owned, int unowned) {
        @Override
        public String toString() {
            return total + " total (" + owned + " owned, " + unowned + " idle)";
        }
    }

    /**
     * Gets current conversation memory statistics.
     *
     * @return snapshot of conversation counts
     */
    public GateStats getStats() {
        int total = conversations.size();
        int owned = 0;
        for (ConversationHolder holder : conversations.values()) {
            if (holder.getOwner() != null) {
                owned++;
            }
        }
        return new GateStats(total, owned, total - owned);
    }

}