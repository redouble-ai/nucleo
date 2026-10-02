/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.observability.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * The runtime's message bus: per-subscriber queues on virtual threads, so a slow consumer
 * never blocks a publisher or another consumer, and three indexes (workflow id, event type,
 * job type) that narrow the candidates before each subscription's own filter judges the event.
 *
 * <p>Lifecycle: a message published before {@link #start()} or after {@link #stop()} is
 * dropped; what was published before {@code stop()} is delivered before it returns, as
 * {@link MessageBus#stop()} promises, each consumer getting up to 5 seconds to drain its
 * queue. A normally-paced observer's backlog is delivered whole; an observer whose backlog
 * cannot clear within that window is interrupted and the remainder dropped - the bound exists
 * so a wedged or slow observer cannot hang shutdown. An unsubscribed observer likewise
 * receives what was already enqueued for it and nothing more; the unsubscribe never blocks
 * its caller. A second {@code start()} is a no-op; {@code stop()} on
 * a bus that is not running, or a second time, throws {@link IllegalStateException}, as does
 * {@code subscribe} while stopping. Each subscription queues up to a million events and drops the rest with an error
 * log. An observer instance that is already subscribed receives a no-op subscription and a
 * warning. An observer that throws is logged and keeps receiving; a predicate that throws
 * rejects that event. Stale subscriptions are reaped every 60 seconds.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public class LinkedQueueMessageBus implements MessageBus {
    private static final Logger log = LoggerFactory.getLogger(LinkedQueueMessageBus.class);

    /**
     * Primary index by workflow ID. Excludes global subscriptions (null workflow).
     */
    private final Map<String, Set<SubscriptionImpl>> subscriptionsByWorkflow;

    /**
     * Secondary index by message type. Excludes JobEvent.class subscriptions.
     */
    private final Map<Class<?>, Set<SubscriptionImpl>> subscriptionsByType;

    /**
     * Tertiary index by job type. Excludes Job.class subscriptions.
     */
    private final Map<Class<? extends Job>, Set<SubscriptionImpl>> subscriptionsByJobType;

    /**
     * Dispatch cache keyed by concrete event class. Populated on cache miss in
     * {@link #findSubscriptionsByMessageType} and invalidated wholesale on any
     * subscribe/unsubscribe. Avoids rescanning {@link #subscriptionsByType} on
     * every publish.
     */
    private final ConcurrentHashMap<Class<?>, SubscriptionImpl[]> typeDispatchCache;

    /**
     * Dispatch cache keyed by concrete job class. Same pattern as
     * {@link #typeDispatchCache} but for {@link #subscriptionsByJobType}.
     */
    private final ConcurrentHashMap<Class<? extends Job>, SubscriptionImpl[]> jobTypeDispatchCache;

    /**
     * Version counter bumped on every subscribe/unsubscribe. Dispatch lookups
     * read this before and after compute and refuse to cache results when the
     * version changed mid-computation, preventing stale entries from persisting
     * across concurrent subscribe-during-publish races.
     */
    private final AtomicLong dispatchVersion = new AtomicLong();

    /**
     * Sentinel returned from dispatch lookups when no subscriptions match.
     * Shared to keep cache misses allocation-free in the empty case.
     */
    private static final SubscriptionImpl[] EMPTY_SUBS = new SubscriptionImpl[0];

    /**
     * Global subscriptions that accept ALL events (workflow=null, type=JobEvent.class, job=Job.class).
     * Stored as a volatile snapshot array so publish() can iterate it directly
     * without allocating. Writers (subscribe/unsubscribe) serialize on
     * {@link #globalsLock} and publish a fresh array on each mutation.
     */
    private volatile SubscriptionImpl[] globalSubscriptions;

    /**
     * Write-side mutex for {@link #globalSubscriptions}. Readers (publish) use
     * the volatile field directly; writers serialize here to avoid lost updates.
     */
    private final Object globalsLock = new Object();

    /**
     * Registry of all active subscriptions.
     */
    private final Map<String, SubscriptionImpl> subscriptionsById;

    /**
     * Set of registered observers to prevent duplicates.
     * Uses observer.equals() to determine uniqueness.
     */
    private final Set<JobObserver<?>> registeredObservers;

    /**
     * Running state.
     */
    private final AtomicBoolean running;

    /**
     * Shutting down state.
     */
    private final AtomicBoolean shuttingDown;

    /**
     * Subscription processing threads.
     */
    private final List<Thread> subscriptionThreads;

    /**
     * Cleanup task executor for periodic stale subscription cleanup.
     */
    private ScheduledThreadPoolExecutor cleanupExecutor;

    /**
     * Cleanup interval in seconds (default: 60 seconds).
     */
    private static final long CLEANUP_INTERVAL_SECONDS = 60;

    /**
     * Check lifecycle state and throw exception if operation not allowed.
     *
     * @param operation description of the operation being attempted
     */
    private void checkLifecycle(String operation) {
        if (shuttingDown.get()) {
            throw new IllegalStateException("Cannot " + operation + ": MessageBus is shutting down");
        }
        if (!running.get()) {
            throw new IllegalStateException("Cannot " + operation + ": MessageBus has not been started. Call start() first");
        }
    }

    public LinkedQueueMessageBus() {
        this.subscriptionsByWorkflow = new ConcurrentHashMap<>();
        this.subscriptionsByType = new ConcurrentHashMap<>();
        this.subscriptionsByJobType = new ConcurrentHashMap<>();
        this.typeDispatchCache = new ConcurrentHashMap<>();
        this.jobTypeDispatchCache = new ConcurrentHashMap<>();
        this.globalSubscriptions = EMPTY_SUBS;
        this.subscriptionsById = new ConcurrentHashMap<>();
        this.registeredObservers = ConcurrentHashMap.newKeySet();
        this.running = new AtomicBoolean(false);
        this.shuttingDown = new AtomicBoolean(false);
        this.subscriptionThreads = Collections.synchronizedList(new ArrayList<>());
    }

    @Override
    public void publish(JobEvent message) {
        // Silently drop if not running or shutting down - events may be published during shutdown
        if (!running.get() || shuttingDown.get()) {
            return;
        }
        // Extract message metadata
        String workflowId = message.snapshot() != null ? message.snapshot().getWorkflowId() : null;
        Class<? extends JobEvent> messageType = message.getClass();
        Class<? extends Job> jobType = message.snapshot() != null ? message.snapshot().jobClass() : null;
        log.debug("Publishing {} with workflow={}, jobType={}",
                messageType.getSimpleName(),
                workflowId,
                jobType != null ? jobType.getSimpleName() : "null");
        int matched = 0;
        // Globals are disjoint from the three indexes by construction (see subscribe()
        // and the isGlobal check), so they dispatch directly with no dedup set.
        SubscriptionImpl[] globals = globalSubscriptions;
        for (SubscriptionImpl sub : globals) {
            if (sub.messageType.isAssignableFrom(messageType) && sub.matches(message)) {
                sub.enqueue(message);
                matched++;
            }
        }
        // Indexed subscriptions can overlap across workflow/type/jobType indexes,
        // so the dedup set is lazily allocated only when some index actually has hits.
        // For the common case (global observers only), zero HashSet allocation.
        Set<SubscriptionImpl> indexed = null;
        if (workflowId != null) {
            Set<SubscriptionImpl> workflowSubs = subscriptionsByWorkflow.get(workflowId);
            if (workflowSubs != null && !workflowSubs.isEmpty()) {
                indexed = new HashSet<>(workflowSubs);
            }
        }
        SubscriptionImpl[] typeSubs = findSubscriptionsByMessageType(messageType);
        if (typeSubs.length > 0) {
            if (indexed == null) {
                indexed = new HashSet<>();
            }
            Collections.addAll(indexed, typeSubs);
        }
        if (jobType != null && jobType != Job.class) {
            SubscriptionImpl[] jobSubs = findSubscriptionsByJobType(jobType);
            if (jobSubs.length > 0) {
                if (indexed == null) {
                    indexed = new HashSet<>();
                }
                Collections.addAll(indexed, jobSubs);
            }
        }
        if (indexed != null) {
            for (SubscriptionImpl sub : indexed) {
                if (sub.messageType.isAssignableFrom(messageType) && sub.matches(message)) {
                    sub.enqueue(message);
                    matched++;
                }
            }
        }
        log.debug("Published to {} subscribers out of {} candidates", matched, globals.length + (indexed != null ? indexed.size() : 0));
    }

    /**
     * Find subscriptions that accept the given message type or its supertypes.
     * Cached by concrete message class; misses rescan {@link #subscriptionsByType}
     * and snapshot the result. Subscribe/unsubscribe clears the cache.
     */
    private SubscriptionImpl[] findSubscriptionsByMessageType(Class<? extends JobEvent> messageType) {
        SubscriptionImpl[] cached = typeDispatchCache.get(messageType);
        if (cached != null) {
            return cached;
        }
        long vBefore = dispatchVersion.get();
        List<SubscriptionImpl> matches = null;
        for (Map.Entry<Class<?>, Set<SubscriptionImpl>> entry : subscriptionsByType.entrySet()) {
            if (entry.getKey().isAssignableFrom(messageType)) {
                if (matches == null) {
                    matches = new ArrayList<>();
                }
                matches.addAll(entry.getValue());
            }
        }
        SubscriptionImpl[] result = (matches == null) ? EMPTY_SUBS : matches.toArray(EMPTY_SUBS);
        // Only cache if no subscribe/unsubscribe happened during compute; otherwise
        // the result may be stale and a subsequent clear() might not cover our put.
        if (dispatchVersion.get() == vBefore) {
            typeDispatchCache.put(messageType, result);
        }
        return result;
    }

    /**
     * Find subscriptions that accept the given job type or its supertypes.
     * Cached by concrete job class; see {@link #findSubscriptionsByMessageType}.
     */
    private SubscriptionImpl[] findSubscriptionsByJobType(Class<? extends Job> jobType) {
        SubscriptionImpl[] cached = jobTypeDispatchCache.get(jobType);
        if (cached != null) {
            return cached;
        }
        long vBefore = dispatchVersion.get();
        List<SubscriptionImpl> matches = null;
        for (Map.Entry<Class<? extends Job>, Set<SubscriptionImpl>> entry : subscriptionsByJobType.entrySet()) {
            if (entry.getKey().isAssignableFrom(jobType)) {
                if (matches == null) {
                    matches = new ArrayList<>();
                }
                matches.addAll(entry.getValue());
            }
        }
        SubscriptionImpl[] result = (matches == null) ? EMPTY_SUBS : matches.toArray(EMPTY_SUBS);
        if (dispatchVersion.get() == vBefore) {
            jobTypeDispatchCache.put(jobType, result);
        }
        return result;
    }

    @Override
    public <T extends JobEvent> Subscription subscribe(Class<? extends Job> jobType, Class<T> messageType, JobObserver<T> observer) {
        return subscribe(jobType, messageType, null, observer);
    }

    @Override
    public <T extends JobEvent> Subscription subscribe(Class<? extends Job> jobType, Class<T> messageType, String workflowId, JobObserver<T> observer) {
        // Allow subscription even when not running - will activate when started
        // But don't allow when shutting down
        if (shuttingDown.get()) {
            throw new IllegalStateException("Cannot subscribe: MessageBus is shutting down");
        }

        // Check for duplicate observer registration
        if (registeredObservers.contains(observer)) {
            log.warn("Observer {} is already registered - skipping duplicate subscription", observer.getClass().getSimpleName());
            return new NoOpSubscription();
        }

        String id = UUID.randomUUID().toString();
        SubscriptionImpl sub = new SubscriptionImpl(id, jobType, messageType, workflowId, observer);

        // Add observer to registered set
        registeredObservers.add(observer);

        // Add to main registry
        subscriptionsById.put(id, sub);

        // Only add to workflow index if this subscription has a specific workflow ID
        // Global subscriptions (workflowId=null) don't need workflow indexing
        if (workflowId != null) {
            subscriptionsByWorkflow.computeIfAbsent(workflowId, k -> new HashSet<>()).add(sub);
        }

        // Only add to type index if this subscription has a specific message type
        // Subscriptions for JobEvent.class (accept all events) are not indexed here
        if (messageType != null && messageType != JobEvent.class) {
            subscriptionsByType.computeIfAbsent(messageType, k -> new HashSet<>()).add(sub);
        }

        // Only add to job type index if this subscription has a specific job type
        // Subscriptions for Job.class (accept all jobs) are not indexed here
        if (jobType != null && jobType != Job.class) {
            subscriptionsByJobType.computeIfAbsent(jobType, k -> new HashSet<>()).add(sub);
        }

        // Check if this is a global subscription (accepts everything)
        boolean isGlobal = (workflowId == null && messageType == JobEvent.class && (jobType == null || jobType == Job.class));
        if (isGlobal) {
            synchronized (globalsLock) {
                SubscriptionImpl[] current = globalSubscriptions;
                SubscriptionImpl[] next = Arrays.copyOf(current, current.length + 1);
                next[current.length] = sub;
                globalSubscriptions = next;
            }
        }
        // Bump version before clearing so any publish currently mid-compute
        // refuses to cache its (potentially stale) result. Wholesale clear
        // sidesteps partial-invalidation races.
        dispatchVersion.incrementAndGet();
        typeDispatchCache.clear();
        jobTypeDispatchCache.clear();

        log.debug("Subscription {} registered: jobType={}, messageType={}, workflow={} (in {} workflow index, {} type index, {} job index)",
                id.substring(0, 8),
                jobType != null ? jobType.getSimpleName() : "null",
                messageType.getSimpleName(),
                workflowId,
                workflowId != null ? 1 : 0,
                messageType != JobEvent.class ? 1 : 0,
                jobType != null && jobType != Job.class ? 1 : 0);

        // Start processing if running
        if (running.get()) {
            sub.start();
        }

        return sub;
    }

    @Override
    public void unsubscribe(Subscription subscription) {
        if (subscription instanceof SubscriptionImpl sub) {
            // Remove observer from registered set
            registeredObservers.remove(sub.observer);

            // Remove from main registry
            subscriptionsById.remove(sub.getId());

            // Remove from workflow index (only if it has a workflow ID)
            if (sub.workflowId != null) {
                Set<SubscriptionImpl> workflowSubs = subscriptionsByWorkflow.get(sub.workflowId);
                if (workflowSubs != null) {
                    workflowSubs.remove(sub);
                    // Clean up empty sets to prevent map growth
                    if (workflowSubs.isEmpty()) {
                        subscriptionsByWorkflow.remove(sub.workflowId);
                    }
                }
            }

            // Remove from type index (only if it's not JobEvent.class)
            if (sub.messageType != null && sub.messageType != JobEvent.class) {
                Set<SubscriptionImpl> typeSubs = subscriptionsByType.get(sub.messageType);
                if (typeSubs != null) {
                    typeSubs.remove(sub);
                    // Clean up empty sets
                    if (typeSubs.isEmpty()) {
                        subscriptionsByType.remove(sub.messageType);
                    }
                }
            }

            // Remove from job type index (only if it's not Job.class)
            if (sub.jobType != null && sub.jobType != Job.class) {
                Set<SubscriptionImpl> jobSubs = subscriptionsByJobType.get(sub.jobType);
                if (jobSubs != null) {
                    jobSubs.remove(sub);
                    // Clean up empty sets
                    if (jobSubs.isEmpty()) {
                        subscriptionsByJobType.remove(sub.jobType);
                    }
                }
            }

            // Remove from global subscriptions if present
            synchronized (globalsLock) {
                SubscriptionImpl[] current = globalSubscriptions;
                int idx = -1;
                for (int i = 0; i < current.length; i++) {
                    if (current[i] == sub) {
                        idx = i;
                        break;
                    }
                }
                if (idx >= 0) {
                    SubscriptionImpl[] next = new SubscriptionImpl[current.length - 1];
                    System.arraycopy(current, 0, next, 0, idx);
                    System.arraycopy(current, idx + 1, next, idx, current.length - idx - 1);
                    globalSubscriptions = next;
                }
            }
            // Bump version then clear; see subscribe() for rationale.
            dispatchVersion.incrementAndGet();
            typeDispatchCache.clear();
            jobTypeDispatchCache.clear();

            // Deliver what was already matched and enqueued, then let the consumer exit
            // on its own once the queue is empty - the index removal above guarantees
            // nothing new arrives. Interrupting here would drop those events, the same
            // defect stop()'s drain closes; nothing joins, so an unsubscribe never blocks
            // its caller.
            sub.beginDrain();
        }
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            // Start all existing subscriptions
            for (SubscriptionImpl sub : subscriptionsById.values()) {
                sub.start();
            }

            // Start cleanup task
            cleanupExecutor = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = Thread.ofVirtual().factory().newThread(r);
                t.setName("msgbus-cleanup");
                return t;
            });
            cleanupExecutor.scheduleAtFixedRate(this::cleanupStaleSubscriptions, CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Override
    public void stop() {
        if (!running.get()) {
            throw new IllegalStateException("Cannot stop: MessageBus is not running");
        }
        if (!shuttingDown.compareAndSet(false, true)) {
            throw new IllegalStateException("MessageBus is already shutting down");
        }
        if (running.compareAndSet(true, false)) {
            // Stop cleanup task
            if (cleanupExecutor != null) {
                cleanupExecutor.shutdownNow();
                try {
                    if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.warn("MessageBus cleanup task did not stop within 5 seconds");
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                cleanupExecutor = null;
            }

            // Drain, then stop. shuttingDown is set, so publish already drops and each
            // queue's content is fixed: what was published before stop. beginDrain turns
            // each consumer from waiting into exiting-on-empty; the 5-second join per
            // consumer exists so a wedged or slow observer cannot hang shutdown - one
            // whose backlog cannot clear within it is interrupted by the stop below and
            // the remainder dropped.
            List<Thread> threads = List.copyOf(subscriptionThreads);
            for (SubscriptionImpl sub : subscriptionsById.values()) {
                sub.beginDrain();
            }
            for (Thread thread : threads) {
                try {
                    thread.join(5000);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // Stop all subscriptions; a drained consumer has already exited
            for (SubscriptionImpl sub : subscriptionsById.values()) {
                sub.stop();
            }

            // Wait for the interrupted stragglers to finish
            for (Thread thread : threads) {
                try {
                    thread.join(5000);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            subscriptionThreads.clear();
        }
    }

    @Override
    public int cleanupStaleSubscriptions() {
        int removedCount = 0;
        List<String> toRemove = new ArrayList<>();

        // Find stale subscriptions
        for (SubscriptionImpl sub : subscriptionsById.values()) {
            try {
                if (sub.observer != null && sub.observer.isStale()) {
                    toRemove.add(sub.getId());
                }
            }
            catch (Exception e) {
                log.error("Error checking staleness for subscription {}: {}", sub.getId(), e.getMessage());
            }
        }

        // Remove stale subscriptions
        for (String id : toRemove) {
            Subscription sub = subscriptionsById.get(id);
            if (sub != null) {
                unsubscribe(sub);
                removedCount++;
                log.debug("Removed stale subscription: {}", id);
            }
        }

        if (removedCount > 0) {
            log.info("Cleaned up {} stale subscriptions", removedCount);
        }

        return removedCount;
    }

    /**
     * Internal subscription implementation.
     */
    private class SubscriptionImpl implements Subscription {
        private final String id;
        private final Class<? extends Job> jobType;
        private final Class<? extends JobEvent> messageType;
        private final String workflowId;
        private final JobObserver<? super JobEvent> observer;
        private final Predicate<? super JobEvent> predicate;
        private final LinkedBlockingQueue<JobEvent> queue;
        private final AtomicBoolean active;
        /** Set by stop()'s drain: the consumer exits when its queue is empty instead of waiting. */
        private volatile boolean draining;
        private Thread processingThread;

        @SuppressWarnings("unchecked")
        SubscriptionImpl(String id, Class<? extends Job> jobType, Class<? extends JobEvent> messageType, String workflowId, JobObserver<?> observer) {
            this.id = id;
            // Normalize null to Job.class to mean "all jobs"
            this.jobType = (jobType != null) ? jobType : Job.class;
            this.messageType = messageType;
            this.workflowId = workflowId;
            this.observer = (JobObserver<? super JobEvent>)observer;
            // Get the predicate from the observer (null means accept all)
            this.predicate = (Predicate<? super JobEvent>)observer.getPredicate();
            this.queue = new LinkedBlockingQueue<>(1_000_000);  // 1M capacity to prevent message loss
            this.active = new AtomicBoolean(false);
        }

        boolean matches(JobEvent message) {
            // A workflow-scoped subscription accepts only its workflow's events. The
            // workflow INDEX is a candidate-narrowing optimization, not the filter: a
            // subscription with a concrete message type is also in the type index, which
            // pulls it as a candidate for every workflow, so membership must be judged here.
            if (workflowId != null) {
                JobSnapshot scopeCheck = message.snapshot();
                if (scopeCheck == null || !workflowId.equals(scopeCheck.getWorkflowId())) {
                    return false;
                }
            }
            // The job type filter; the constructor normalizes a null jobType to Job.class
            // Get the job class from the message
            JobSnapshot descriptor = message.snapshot();
            if (descriptor == null) {
                // System-level events (scheduler events) have no descriptor
                // Only accept if we're subscribing to all jobs
                if (jobType != Job.class) {
                    return false;
                }
            }
            else {
                Class<? extends Job> messageJobClass = descriptor.jobClass();
                if (messageJobClass == null) {
                    // Message has no job class info - only accept if we're subscribing to all jobs
                    if (jobType != Job.class) {
                        return false;
                    }
                }
                else {
                    // Check if the message's job type is compatible with our filter
                    // If we subscribe to PapaJob, we want PapaJob and all its subclasses (ChildJob, etc.)
                    // So we check: is messageJobClass a subclass of (or equal to) jobType?
                    if (!jobType.isAssignableFrom(messageJobClass)) {
                        return false;
                    }
                }
            }

            // Now check the predicate if one was provided
            // Message type check is done by the bus
            if (predicate != null) {
                try {
                    return predicate.test(message);
                }
                catch (Exception e) {
                    log.error("Error in subscription predicate: {}", e.getMessage());
                    return false;  // Safer to reject on error
                }
            }

            return true;  // No predicate means accept all (after job type filtering)
        }

        @Override
        public String toString() {
            return "Subs" + observer.getClass().getSimpleName() + "(" + id + ")";
        }

        void enqueue(JobEvent message) {
            if (active.get()) {
                // Non-blocking offer
                if (!queue.offer(message)) {
                    // Queue full - log and drop
                    log.error("Message queue full for subscription: {}", id);
                }
            }
        }

        void start() {
            if (active.compareAndSet(false, true)) {
                processingThread = Thread.ofVirtual().name("msgbus-" + id.substring(0, 8)).start(this::processMessages);
                subscriptionThreads.add(processingThread);
            }
        }

        void beginDrain() {
            draining = true;
        }

        void stop() {
            active.set(false);
            if (processingThread != null) {
                processingThread.interrupt();
                // Remove this thread from the subscription threads list
                subscriptionThreads.remove(processingThread);
            }
        }

        private void processMessages() {
            while (active.get()) {
                try {
                    JobEvent message = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (message == null) {
                        // Draining and empty: everything published before stop is delivered
                        if (draining) {
                            break;
                        }
                        continue;
                    }
                    try {
                        // Event already filtered by predicate, just observe it
                        observer.observe(message);
                    }
                    catch (Exception e) {
                        // Log but don't stop processing
                        log.error("Error in message observer: {}", e.getMessage(), e);
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // A consumer that exited by drain removes itself; stop()'s removal covers the rest
            active.set(false);
            subscriptionThreads.remove(Thread.currentThread());
        }

        @Override
        public void unsubscribe() {
            LinkedQueueMessageBus.this.unsubscribe(this);
        }

        @Override
        public String getId() {
            return id;
        }
    }

    /**
     * No-op subscription returned when attempting to register a duplicate observer.
     */
    private static class NoOpSubscription implements Subscription {
        @Override
        public void unsubscribe() {
        }

        @Override
        public String getId() {
            return "noop";
        }
    }
}