/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.events.heartbeat.*;
import ai.redouble.nucleo.events.retry.*;
import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * The runtime: one dispatcher per process, the entry point for every job submission and the
 * one execution path every job takes. A submission passes the door (authority, parent, scope),
 * is queued by its timeout (five seconds or less to the priority queue, longer to the FIFO
 * queue, none at all when it declines queueing), and executes on a virtual thread: dependencies
 * awaited, input guards, model resolution and pricing, admission of the whole demand, the
 * two-phase timeout armed, the job's own work under an optional transaction, output guards,
 * and one terminal event. Upstream throttling, a truncated answer and a correctable answer
 * re-run the attempt transparently; a cancellation and a timeout end it. The dispatcher also
 * owns the message bus, the admission monitor, the heartbeat engine, the spend gates and the
 * compliance envelope, and stops all of them at shutdown. {@code harness/PACKAGE.md} states
 * the contract; the section "The dispatch contract" carries the numbers.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-18)
 */
public enum JobDispatcher {
    INSTANCE;
    // An instance field: enum statics initialize after INSTANCE, and the constructor already logs
    private final Logger log = LoggerFactory.getLogger(JobDispatcher.class);

    /**
     * Backstop for response-correction retries. Jobs that throw
     * {@link ai.redouble.nucleo.harness.errors.retry.ResponseCorrectionRetryException} track their own
     * correction budget (see {@code LLMCall.MAX_CORRECTIONS}); this cap only
     * stops a buggy job from looping forever.
     */
    private static final int MAX_CORRECTION_RETRIES = 4;


    /**
     * Lifecycle states for the dispatcher.
     */
    private enum State {
        STOPPED,   // Initial state, can transition to STARTING
        STARTING,  // start() in progress, can transition to STARTED
        STARTED,   // Fully started, can transition to STOPPING
        STOPPING   // shutdown() in progress, can transition to STOPPED
    }


    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private final MessageBus messageBus;
    private volatile Heart heart;
    private volatile Admission admission;

    // Unified execution components
    private ExecutorService virtualExecutor;
    private ScheduledExecutorService timeoutExecutor;
    private final BlockingQueue<QueuedJob<?>> standardQueue = new LinkedBlockingQueue<>();
    private final PriorityBlockingQueue<QueuedJob<?>> fastQueue = new PriorityBlockingQueue<>();
    private final Set<JobHandle<?>> runningJobs = Collections.synchronizedSet(new HashSet<>());
    /**
     * Live jobs by id, from admitted dispatch until executeJobUnified's finally.
     * The submission door's parent/thread consistency rule keys on it: naming a live
     * job as parent is legal only from that job's own thread. QUEUED counts as live.
     * Removal contract: executeJobUnified's finally; on shutdown the queues are
     * abandoned, so entries for queued-never-executed jobs persist only into a dying
     * process - not a leak.
     */
    private final ConcurrentHashMap<String, JobContext<?>> liveJobs = new ConcurrentHashMap<>();
    private final Set<Stoppable> stoppables = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private Thread standardQueueThread;
    private Thread fastQueueThread;

    // Configuration
    private final SchedulerConfig config;

    /**
     * The one instance, under the name callers read most naturally; the same object as
     * {@link #INSTANCE}.
     */
    public static JobDispatcher getInstance() {
        return INSTANCE;
    }

    /**
     * Creates the dispatcher.
     * Called once by JVM when enum singleton is initialized.
     */
    JobDispatcher() {
        this.messageBus = new LinkedQueueMessageBus();
        this.config = SchedulerConfig.defaults();

        registerForShutdown(MemoryPressureGate.getInstance());
        log.info("JobDispatcher initialized");
    }

    /**
     * The application's compliance envelope - one compliance domain per dispatcher, which
     * is one per application. Sealed once by the host through
     * {@link #sealComplianceEnvelope} before any job runs; {@link #start()} seals the refusing default if nothing was declared.
     * Never changeable after: a switch flipped later would only promise "compliant from
     * now on", which is not the property anyone wants.
     */
    private volatile ComplianceEnvelope complianceEnvelope;
    private static final ComplianceEnvelope REFUSING_DEFAULT = new DefaultComplianceEnvelope();

    /** Seals the envelope - once, permanently. A second seal throws. */
    public synchronized void sealComplianceEnvelope(ComplianceEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("Cannot seal a null compliance envelope");
        }
        if (complianceEnvelope != null) {
            throw new IllegalStateException("Compliance envelope already sealed to "
                    + complianceEnvelope.getClass().getSimpleName() + " - the seal is permanent for the process lifetime");
        }
        complianceEnvelope = envelope;
        log.info("Compliance envelope sealed: {}", envelope.getClass().getSimpleName());
    }

    /**
     * The sealed envelope. A read before any seal returns the refusing default WITHOUT
     * sealing, so an early reader gets deterministic default-deny and the servlet's later
     * legitimate seal still lands.
     */
    public ComplianceEnvelope getComplianceEnvelope() {
        ComplianceEnvelope sealed = complianceEnvelope;
        return sealed != null ? sealed : REFUSING_DEFAULT;
    }

    /** Test hook: clears the seal. Package-private. */
    void resetComplianceEnvelopeForTests() {
        complianceEnvelope = null;
    }

    /**
     * The money walls: every registered {@link SpendGate} is consulted after an attempt's
     * bindings are resolved and priced, before any resource is acquired. Registration is for
     * the process lifetime, like a subscription; a gate that wants to stop gating removes
     * its own caps.
     */
    private final List<SpendGate> spendGates = new CopyOnWriteArrayList<>();

    public void registerSpendGate(SpendGate gate) {
        if (gate == null) {
            throw new IllegalArgumentException("Cannot register a null spend gate");
        }
        spendGates.add(gate);
        log.info("Spend gate registered: {}", gate.getClass().getSimpleName());
    }

    /**
     * Submits a job for execution without dependencies.
     * Gets userId from job.getUserId() (via Identifiable interface).
     *
     * @param <T> the job's return type
     * @param job the job to submit
     * @return a handle to monitor and control the job
     */
    public <T> JobHandle<T> submit(Job<T> job) {
        return dispatch(job, null, null);
    }

    /**
     * Submits a job for execution with initial metadata seeded into the job's context.
     * The metadata is seeded before any lifecycle event (JobScheduled, JobStartedEvent,
     * TerminalEvent) fires for the job, so all downstream observers see the seeded keys
     * in the snapshot's metadata map.
     *
     * <p>The dispatcher is semantically opaque about the contents: it forwards the map
     * into the child's {@link JobContext} via {@link JobContext#putMetadata} and does
     * not interpret any key. Callers choose the key namespace (e.g.
     * {@code AbstractOrchestrator.META_ITERATION}, {@code Tool.OBS_INPUT}).
     *
     * @param <T>             the job's return type
     * @param job             the job to submit
     * @param initialMetadata metadata entries to seed into the job's context, or null for none
     * @return a handle to monitor and control the job
     */
    public <T> JobHandle<T> submit(Job<T> job, Map<String, Object> initialMetadata) {
        return dispatch(job, initialMetadata, null);
    }

    /**
     * Submits a job with a single typed dependency.
     * Type-safe method where the dependency type D is captured at compile time.
     * <p>
     * <b>Usage Example:</b>
     * <pre>{@code
     * JobHandle<Data> dataHandle = dispatcher.submit(extractJob);
     * JobHandle<Report> reportHandle = dispatcher.submit(reportJob, dataHandle);
     * // reportJob can access Data result via context.singleDependencyResult()
     * }</pre>
     *
     * @param <T>        the job's return type
     * @param <D>        the dependency's return type
     * @param job        the job to submit
     * @param dependency the single job that must complete first
     * @return a handle to monitor and control the job
     */
    public <T, D> JobHandle<T> submit(Job<T> job, JobHandle<D> dependency) {
        return submit(job, Collections.singletonList(dependency));
    }

    /**
     * Submits a job with multiple dependencies.
     * <p>
     * For homogeneous dependencies (all same type D):
     * <pre>{@code
     * List<JobHandle<Data>> dataHandles = List.of(h1, h2, h3);
     * JobHandle<Report> report = dispatcher.submit(synthJob, dataHandles);
     * // Inside synthJob.execute():
     * List<Data> allData = context.allDependencyResults();
     * }</pre>
     * <p>
     * For heterogeneous dependencies (mixed types):
     * <pre>{@code
     * List<JobHandle<?>> mixedHandles = List.of(dataHandle, configHandle, statusHandle);
     * JobHandle<Report> report = dispatcher.submit(synthJob, mixedHandles);
     * // Inside synthJob.execute():
     * Map<JobSnapshot, Object> results = context.getDependencyResults();
     * for (var entry : results.entrySet()) {
     *     if (entry.getKey().jobClass() == DataJob.class) {
     *         Data data = (Data) entry.getValue();
     *     }
     * }
     * }</pre>
     *
     * @param <T>          the job's return type
     * @param <D>          the dependency type (for homogeneous), or ? for heterogeneous
     * @param job          the job to submit
     * @param dependencies list of jobs that must complete first
     * @return a handle to monitor and control the job
     */
    public <T, D> JobHandle<T> submit(Job<T> job, List<JobHandle<D>> dependencies) {
        return submit(job, null, dependencies);
    }

    /**
     * Submits a job with initial metadata and dependencies - the full form the two
     * simpler overloads delegate to. Orchestrators use it to stamp their bookkeeping
     * metadata onto dependency-sequenced children.
     *
     * @param <T>             the job's return type
     * @param job             the job to submit
     * @param initialMetadata metadata visible on the context from the start, or null
     * @param dependencies    jobs that must complete first, or null
     * @return a handle to monitor and control the job
     */
    public <T> JobHandle<T> submit(Job<T> job, Map<String, Object> initialMetadata, List<? extends JobHandle<?>> dependencies) {
        Collection<JobHandle<?>> deps = null;
        if (dependencies != null && !dependencies.isEmpty()) {
            validateNoCycles(job.getId(), job.getClass().getSimpleName(), dependencies);
            deps = new ArrayList<>(dependencies);
        }
        // Dependencies must be on the context BEFORE the job is queued, otherwise
        // a worker can pick the job up and observe an empty dependency list.
        return dispatch(job, initialMetadata, deps);
    }

    /**
     * Submits a job after a delay using a virtual thread.
     * The delay allows parent transactions to commit before the job starts,
     * solving race conditions where a job needs to see committed data.
     * <p>
     * For root-level jobs only: the delayed submission happens on a fresh virtual
     * thread, which is not any live job's executing thread, so the submission door
     * admits it only when the job's parent is not a live job - exactly the shape of
     * a workflow root. An orchestrator that wants delayed work inside its subtree
     * sequences it behind a dependency instead.
     * <p>
     * <b>Usage Example:</b>
     * <pre>{@code
     * // Submit job with 500ms delay to ensure transaction commits
     * dispatcher.submitWithDelay(
     *     new ReindexJob(root, documentId),
     *     Duration.ofMillis(500)
     * ).thenAccept(handle -> {
     *     log.info("Job started: {}", handle.getJobId());
     * });
     * }</pre>
     *
     * @param <T>   the job's return type
     * @param job   the job to submit
     * @param delay the delay before submission (null, zero, or negative means immediate)
     * @return CompletableFuture that completes with the JobHandle after the delay
     */
    public <T> CompletableFuture<JobHandle<T>> submitWithDelay(Job<T> job, Duration delay) {
        CompletableFuture<JobHandle<T>> future = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                if (delay != null && !delay.isZero() && !delay.isNegative()) {
                    Thread.sleep(delay.toMillis());
                }
                JobHandle<T> handle = submit(job);
                future.complete(handle);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
            }
            catch (Throwable t) {  // Catch Throwable to ensure future completes on Error
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Internal dispatch method.
     * <p>Seeds {@code initialMetadata} (if non-null) into the child context BEFORE
     * the first lifecycle event fires, so every downstream snapshot observer
     * (JobScheduled, JobStartedEvent, OrchestratorResumedEvent/IdleEvent,
     * TerminalEvent) sees the keys.
     */
    private <T> JobHandle<T> dispatch(Job<T> job, Map<String, Object> initialMetadata, Collection<JobHandle<?>> dependencies) {
        return dispatch(job, initialMetadata, dependencies, true);
    }

    /**
     * The internal door: guardrail jobs running on a gated job's behalf, from the gated
     * job's thread, submit here. The authority and parent walls of the public door do
     * not apply - the gated job may be a leaf tool - but the scope wall does, against
     * the guard the gated job itself was admitted under: a scoped guard's claim is
     * judged, and a guard that is a {@link ScopeAuthority} is sealed, exactly as at the
     * public door. A refusal throws to the caller instead of minting a born-failed
     * handle: no job exists yet, so there is nothing to fail and nothing to record.
     */
    <T> JobHandle<T> submitInternal(Job<T> job, JobContext<?> gated) throws LLMReadableCheckedException {
        judgeAndSeal(job, flowGuardOf(gated));
        return dispatch(job, null, null, false);
    }

    /**
     * The guard a gated job was admitted under. An orchestrator carries it sealed on
     * itself. A leaf carries nothing, but its live parent is its submitter by the
     * parent wall, and the parent's sealed guard is precisely what the leaf's claim was
     * judged against at its own admission. A root that nothing binds yields null.
     */
    private ScopeGuard flowGuardOf(JobContext<?> gated) {
        if (gated.getJob() instanceof ScopeAuthority authority) {
            return authority.getScopeGuard();
        }
        String parentId = gated.getJob().getParentJobId();
        JobContext<?> parent = parentId == null ? null : liveJobs.get(parentId);
        if (parent != null && parent.getJob() instanceof ScopeAuthority authority) {
            return authority.getScopeGuard();
        }
        return null;
    }

    private <T> JobHandle<T> dispatch(Job<T> job, Map<String, Object> initialMetadata, Collection<JobHandle<?>> dependencies, boolean throughPublicDoor) {
        ensureStarted();

        JobContext<?> callerContext = JobContext.currentJob();
        if (throughPublicDoor) {
            try {
                admitSubmission(job, callerContext, null);
            }
            catch (LLMReadableCheckedException refusal) {
                return refusedHandle(job, refusal);
            }
        }

        JobRequirements requirements = job.getRequirements();
        Duration timeout = job.getTimeout();
        // A resource-holding job must bound its execution: admission's progress argument
        // rests on every held permit returning in finite time.
        if (requirements != null && requirements.requiresResources() && timeout == null) {
            return refusedHandle(job, new SystemException("JobDispatcher",
                    job.getClass().getSimpleName() + " declares resources but no timeout. Every resource-holding job "
                            + "must declare a timeout so the permits it holds return in finite time.", null));
        }

        String userId = job.getUserId();
        // Create job context
        JobContext<T> context = createContext(job, userId, requirements);
        JobHandle<T> handle = new JobHandle<>(context);

        if (initialMetadata != null && !initialMetadata.isEmpty()) {
            initialMetadata.forEach(context::putMetadata);
        }
        // Apply dependencies before enqueueing - executors read getDependencies() at the
        // top of executeJobUnified, so any setter that runs after enqueue races them.
        if (dependencies != null && !dependencies.isEmpty()) {
            context.setDependencies(dependencies);
        }

        // Create queued job wrapper. The submit-time requirements capture rides along so
        // pre-execution reads (queue routing, dependency tolerance) never re-invoke
        // getRequirements - each invocation mints fresh model bindings, so the dispatcher
        // calls it exactly once at submit and once per execution attempt.
        QueuedJob<T> queuedJob = new QueuedJob<>(job);
        queuedJob.setJobHandle(handle);
        queuedJob.setUserId(userId);
        queuedJob.setContext(context);
        queuedJob.setRequirements(requirements);

        if (callerContext != null) {
            context.putMetadata(GuardrailEnforcer.OBS_CALLER_CLASS, callerContext.getJob().getClass().getName());
        }
        liveJobs.put(context.getJobId(), context);

        // Publish JobScheduled event with QUEUED state
        // Update context state to match
        context.setState(JobState.QUEUED);
        publishEvent(new JobScheduled(context.getSnapshot(), JobScheduled.State.QUEUED, requirements, job.getPriority()));

        // Route based on requirements
        if (requirements != null && !requirements.requiresQueueing()) {
            // Execute immediately on virtual thread - no queueing (instant execution)
            virtualExecutor.submit(() -> executeJobUnified(queuedJob));
        }
        else if (timeout != null && timeout.toSeconds() <= 5) {
            // Fast queue for jobs ≤5 seconds (null timeout = no deadline, never fast-queue)
            fastQueue.offer(queuedJob);
        }
        else {
            // Standard queue for longer jobs
            standardQueue.add(queuedJob);
        }

        return handle;
    }

    /**
     * Creates a job context with the appropriate timeout, reusing the submit-time
     * requirements capture.
     */
    private <T> JobContext<T> createContext(Job<T> job, String userId, JobRequirements requirements) {
        Duration timeout = job.getTimeout();
        return new JobContext<>(job, userId, timeout, requirements);
    }

    /**
     * The submission rules, run before anything else exists for the job - no context,
     * no handle, no events. Three walls, in order:
     * <ol>
     *   <li><b>Submission authority</b>: only orchestrators submit jobs from within
     *       their execution. A resource-holding job coordinating children is the
     *       design error the orchestrator/tool split exists to prevent.</li>
     *   <li><b>Parent/thread consistency</b>: naming a live job as parent is legal
     *       only from that job's own thread. A thread outside any job can only create
     *       new roots - legitimate establishment - never inject into a running flow.</li>
     *   <li><b>Scope</b>: the calling orchestrator's sealed guard judges the child's
     *       claim-carrying input and, for a child orchestrator, the child's own scope
     *       value - a wrong-scope sub-flow dies at establishment, synchronously, with
     *       a message naming both values. A child orchestrator's effective guard is
     *       then composed (own field, own scope, caller's guard) and sealed for its
     *       lifetime: inheritance happens at the one door every submission uses.</li>
     * </ol>
     * A refusal throws; the caller receives it as a born-failed handle.
     * <p>
     * A heartbeat fire is the deferred continuation of the chain that scheduled it: it
     * has no live caller context, and {@code firedGuard} - the publisher's guard
     * captured at schedule time - plays the caller-guard role in the same judgment and
     * merge. One door for live and deferred submissions alike.
     */
    private void admitSubmission(Job<?> job, JobContext<?> callerContext, ScopeGuard firedGuard) throws LLMReadableCheckedException {
        requireSubmissionAuthority(job, callerContext);
        judgeAndSeal(job, callerContext != null ? ((ScopeAuthority) callerContext.getJob()).getScopeGuard() : firedGuard);
    }

    /**
     * The first two walls: submission authority and parent/thread consistency. The
     * public door's rules for user job code; the internal door skips them because a
     * guard is submitted on a gated job's behalf, and the gated job may be a leaf.
     */
    private void requireSubmissionAuthority(Job<?> job, JobContext<?> callerContext) throws LLMReadableCheckedException {
        if (callerContext != null && !(callerContext.getJob() instanceof ScopeAuthority)) {
            throw new SystemException("JobDispatcher",
                    "Only orchestrators may submit jobs: " + callerContext.getJob().getClass().getSimpleName()
                            + " (job " + callerContext.getJobId() + ") attempted to submit "
                            + job.getClass().getSimpleName() + ". Coordinate through a Doer or Thinker.", null);
        }
        String parentId = job.getParentJobId();
        boolean parentIsLive = parentId != null && liveJobs.containsKey(parentId);
        if (callerContext != null && parentIsLive && !parentId.equals(callerContext.getJobId())) {
            throw new SystemException("JobDispatcher",
                    "Submission of " + job.getClass().getSimpleName() + " names live job " + parentId
                            + " as parent but runs on the thread of " + callerContext.getJobId()
                            + ". A child of a running job must be submitted from that job's own execution.", null);
        }
        if (callerContext == null && parentIsLive) {
            throw new SystemException("JobDispatcher",
                    "Submission of " + job.getClass().getSimpleName() + " from outside any job names live job "
                            + parentId + " as parent. Threads outside the job system may only create new roots.", null);
        }
    }

    /**
     * The scope wall, shared by every door: the caller guard judges the child's own
     * scope and the child input's claim, and a child that is a {@link ScopeAuthority}
     * has its effective guard composed and sealed. Null caller guard means nothing
     * binds the submission - a root, or a flow with no scope.
     */
    private void judgeAndSeal(Job<?> job, ScopeGuard callerGuard) throws LLMReadableCheckedException {
        if (callerGuard != null) {
            if (job instanceof Scoped scopedChild) {
                callerGuard.passAll(scopeOf(scopedChild));
            }
            if (job instanceof GuardedExecution guarded && guarded.guardedInputTarget() instanceof Scoped scopedInput) {
                callerGuard.passAll(scopeOf(scopedInput));
            }
        }
        if (job instanceof ScopeAuthority child) {
            ScopeGuard effective = child.getScopeGuard();
            if (job instanceof Scoped scopedSelf) {
                ScopeGuard own = new ScopeGuard(scopeOf(scopedSelf));
                effective = effective == null ? own : effective.merge(own);
            }
            if (callerGuard != null) {
                effective = effective == null ? callerGuard : effective.merge(callerGuard);
            }
            child.sealScopeGuard(effective);
        }
    }

    /**
     * Evaluates a carrier's scope at the door. {@code scope()} is user code: a throw
     * or a null return becomes the refusal's cause - never a raw escape out of submit
     * into a caller that never agreed to catch it.
     */
    private Scope scopeOf(Scoped carrier) throws LLMReadableCheckedException {
        Scope scope;
        try {
            scope = carrier.scope();
        }
        catch (Exception e) {
            throw new SystemException("JobDispatcher",
                    "scope() of " + carrier.getClass().getSimpleName() + " failed at the submission door", e);
        }
        if (scope == null) {
            throw new SystemException("JobDispatcher",
                    "scope() of " + carrier.getClass().getSimpleName() + " returned null: a scoped carrier "
                            + "must produce its scope value at submission", null);
        }
        return scope;
    }

    /**
     * A refused submission's result: a handle born failed with the refusal as cause.
     * The child never existed as a scheduled job - JobScheduled never fires - but the
     * failure is a real terminal event, so fire-and-forget submitters leave a trace.
     */
    private <T> JobHandle<T> refusedHandle(Job<T> job, LLMReadableCheckedException refusal) {
        // Refusal can precede the requirements capture, and a born-failed context never
        // acquires resources - so no requirements are consulted here.
        JobContext<T> context = createContext(job, job.getUserId(), null);
        JobHandle<T> handle = new JobHandle<>(context);
        context.setState(JobState.FAILED);
        publishEvent(new JobFailedEvent(context.getSnapshot(), refusal, 0, context.getLlmResponses(), context.getAllMetadata()));
        publishWorkflowFailedIfRoot(context);
        handle.fail(refusal);
        return handle;
    }

    /**
     * Starts the dispatcher.
     * Schedulers are started on demand when jobs are submitted.
     */
    public void start() {
        if (!state.compareAndSet(State.STOPPED, State.STARTING)) {
            State current = state.get();
            if (current == State.STARTED) {
                log.warn("JobDispatcher already started");
            }
            else {
                log.warn("JobDispatcher in state {}", current);
            }
            return;
        }

        try {
            log.info("Starting JobDispatcher");

            // Start message bus
            messageBus.start();

            // Start unified execution components
            startUnifiedExecution();

            // The one place a job waits for resources; the memory gate is its first conjunct
            admission = new Admission(MemoryPressureGate.getInstance());
            admission.start();

            // The heartbeat engine exists in every dispatcher context, subscribed before
            // any publish from the host's startup can race it (bus-direct: the dispatcher is
            // not STARTED yet, so the public subscribe's guard would refuse)
            heart = new Heart(new InMemoryHeartbeatStore(), this);
            messageBus.subscribe(null, HeartbeatEvent.class, heart);
            registerForShutdown(heart);

            // An application that declared no envelope runs under the refusing default
            if (complianceEnvelope == null) {
                sealComplianceEnvelope(REFUSING_DEFAULT);
            }

            // Transition to STARTED state
            state.set(State.STARTED);

            // Publish scheduler started event
            publishEvent(new SchedulerEvent(SchedulerEvent.Type.STARTED));

            log.info("JobDispatcher started successfully");
        }
        catch (Throwable t) {  // Catch Throwable to ensure state rollback on Error
            // Rollback to STOPPED on failure
            state.set(State.STOPPED);
            throw t;
        }
    }

    /**
     * Ensures the dispatcher has been started.
     *
     * @throws IllegalStateException if the dispatcher is not started
     */
    private void ensureStarted() {
        if (state.get() != State.STARTED) {
            throw new IllegalStateException("JobDispatcher is not started. Call start() first.");
        }
    }

    /**
     * Starts the unified execution components.
     */
    private void startUnifiedExecution() {
        // Create executors
        virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        timeoutExecutor = Executors.newScheduledThreadPool(2, r -> Thread.ofPlatform().name("timeout-enforcer").daemon(true).unstarted(r));

        // Start queue processing threads
        standardQueueThread = Thread.ofVirtual().name("unified-standard-queue").start(this::processStandardQueue);

        fastQueueThread = Thread.ofVirtual().name("unified-fast-queue").start(this::processFastQueue);

        log.info("Started unified execution components");
    }

    /**
     * Processes jobs from the standard queue.
     */
    private void processStandardQueue() {
        while (!shuttingDown.get()) {
            try {
                QueuedJob<?> job = standardQueue.poll(config.schedulerLoopDelayMillis(), TimeUnit.MILLISECONDS);
                if (job != null) {
                    Future<?> executionFuture = virtualExecutor.submit(() -> executeJobUnified(job));
                    job.setExecutionFuture(executionFuture);
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            catch (Throwable t) {  // Catch Throwable to keep queue thread alive on Error
                // Log error but keep scheduler running
                log.error("Error processing standard queue: {}", t.getMessage());
                log.error(t.getMessage(), t);
                publishEvent(new SchedulerEvent(t));
            }
        }
    }

    /**
     * Processes jobs from the fast queue.
     */
    private void processFastQueue() {
        while (!shuttingDown.get()) {
            try {
                QueuedJob<?> job = fastQueue.poll(config.schedulerLoopDelayMillis(), TimeUnit.MILLISECONDS);
                if (job != null) {
                    Future<?> executionFuture = virtualExecutor.submit(() -> executeJobUnified(job));
                    job.setExecutionFuture(executionFuture);
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            catch (Throwable t) {  // Catch Throwable to keep queue thread alive on Error
                // Log error but keep scheduler running
                log.error("Error processing fast queue: {}", t.getMessage());
                log.error(t.getMessage(), t);
                publishEvent(new SchedulerEvent(t));
            }
        }
    }

    /** The graceful drain window a {@link #shutdown} caller uses when it has no deployment-specific number. */
    public static final long DEFAULT_SHUTDOWN_TIMEOUT_MS = 30_000;

    /**
     * Shuts down the dispatcher and all active schedulers.
     *
     * @param timeoutMs maximum time to wait for shutdown
     */
    public void shutdown(long timeoutMs) {
        if (!state.compareAndSet(State.STARTED, State.STOPPING)) {
            State current = state.get();
            if (current == State.STOPPED) {
                log.warn("JobDispatcher not started");
            }
            else {
                log.warn("JobDispatcher in state {}", current);
            }
            return;
        }

        log.info("Shutting down JobDispatcher");

        // Publish scheduler stopping event
        publishEvent(new SchedulerEvent(SchedulerEvent.Type.STOPPING));

        // Signal shutdown
        shuttingDown.set(true);

        // Refuse every parked waiter first, so jobs waiting for resources end at once
        // instead of counting as running for the whole shutdown timeout
        if (admission != null) {
            admission.stop();
        }

        // Shutdown unified execution
        shutdownUnifiedExecution(timeoutMs);

        // Stop all registered stoppables
        log.info("Stopping {} registered components", stoppables.size());
        for (Stoppable stoppable : stoppables) {
            String name = stoppable.getClass().getSimpleName();
            try {
                log.info("Stopping: {}", name);
                stoppable.stop();
                log.info("Stopped: {}", name);
            }
            catch (Exception e) {
                log.error("Error stopping {}: {}", name, e.getMessage());
            }
        }

        // Stop message bus
        try {
            messageBus.stop();
        }
        catch (Exception e) {
            log.error("Error stopping message bus: {}", e.getMessage());
        }

        // Transition to STOPPED state
        state.set(State.STOPPED);

        // Publish scheduler stopped event
        publishEvent(new SchedulerEvent(SchedulerEvent.Type.STOPPED));

        log.info("JobDispatcher shutdown complete");
    }

    /**
     * Shuts down unified execution components.
     */
    private void shutdownUnifiedExecution(long timeoutMs) {
        // Use config timeout if provided timeout is default
        long effectiveTimeout = timeoutMs > 0 ? timeoutMs : config.shutdownTimeoutMillis();
        // Stop queue processing threads
        if (standardQueueThread != null) {
            standardQueueThread.interrupt();
        }
        if (fastQueueThread != null) {
            fastQueueThread.interrupt();
        }

        // Every job runs on the virtual executor, so awaiting its termination is waiting for
        // the running jobs; what has not finished by the deadline is interrupted
        if (virtualExecutor != null) {
            virtualExecutor.shutdown();
            try {
                if (!virtualExecutor.awaitTermination(effectiveTimeout, TimeUnit.MILLISECONDS)) {
                    virtualExecutor.shutdownNow();
                }
            }
            catch (InterruptedException e) {
                virtualExecutor.shutdownNow();
            }
        }

        if (timeoutExecutor != null) {
            timeoutExecutor.shutdown();
            try {
                if (!timeoutExecutor.awaitTermination(effectiveTimeout / 2, TimeUnit.MILLISECONDS)) {
                    timeoutExecutor.shutdownNow();
                }
            }
            catch (InterruptedException e) {
                timeoutExecutor.shutdownNow();
            }
        }

        // Drain queues and publish cancellation events
        drainQueues();

        log.info("Unified execution components shutdown");
    }

    /**
     * Drains all queues and publishes cancellation events.
     */
    private void drainQueues() {
        List<QueuedJob<?>> cancelledJobs = new ArrayList<>();

        // Drain standard queue
        standardQueue.drainTo(cancelledJobs);

        // Drain fast queue
        fastQueue.drainTo(cancelledJobs);

        // A job taken out of a queue by shutdown never runs: settle its state, its event and
        // its handle exactly as a cancellation does, so no waiter is left parked on a job
        // that no longer exists
        for (QueuedJob<?> queuedJob : cancelledJobs) {
            settleCancelledWhileQueued(queuedJob, "Cancelled by shutdown");
        }
    }

    /**
     * Checks if the dispatcher is running.
     */
    public boolean isRunning() {
        return state.get() == State.STARTED;
    }

    /**
     * Registers a component for graceful shutdown.
     */
    public void registerForShutdown(Stoppable stoppable) {
        stoppables.add(stoppable);
        log.info("Registered for shutdown: {}", stoppable.getClass().getSimpleName());
    }

    /**
     * Gets the message bus for event subscription.
     * Public to allow advanced usage, but prefer using the subscription methods.
     */
    public MessageBus getMessageBus() {
        return messageBus;
    }

    /** The heartbeat engine, for operator surfaces (store inspection, cancellation). */
    public Heart getHeart() {
        ensureStarted();
        return heart;
    }

    /**
     * The authority-bearing heartbeat entry for code OUTSIDE any job - boot wiring, an
     * operator endpoint with an authenticated session. Demands a principal exactly like
     * {@code Job.workflow}; the platform threat model trusts code, so a trusted caller
     * naming a principal here is the same posture as every root-workflow submission.
     * Jobs never call this: an orchestrator schedules by publishing the spec through
     * {@code JobContext.publish}, which captures its identity and sealed guard; a
     * non-orchestrator cannot schedule at all.
     */
    public void scheduleHeartbeat(ScheduleHeartbeat spec, String userId) {
        ensureStarted();
        publishEvent(new HeartbeatRequested(new Heartbeat(spec, spec.runAt(), userId, null)));
    }

    /**
     * The Heart's fire-time submission: the deferred continuation of the schedule-time
     * chain. Runs the SAME door as a live submission, with the captured guard playing
     * the caller-guard role - judgment and merge both - so future door hardening
     * applies to fires automatically.
     */
    <T> JobHandle<T> submitFired(Job<T> job, ScopeGuard capturedGuard) {
        ensureStarted();
        try {
            admitSubmission(job, null, capturedGuard);
        }
        catch (LLMReadableCheckedException refusal) {
            return refusedHandle(job, refusal);
        }
        return dispatch(job, null, null, false);
    }

    /**
     * Subscribes an observer to job events.
     * This is the main public API for event subscription.
     *
     * @param <T>       the event type
     * @param observer  the observer to register
     * @param eventType the type of events to observe
     * @return a subscription handle that can be used to unsubscribe
     */
    public <T extends JobEvent> MessageBus.Subscription subscribe(JobObserver<T> observer, Class<T> eventType) {
        ensureStarted();
        // Subscribe to all job types (null) with the given event type and observer
        return messageBus.subscribe(null, eventType, observer);
    }

    /**
     * Subscribes an observer to job events from specific job types.
     *
     * @param <T>       the event type
     * @param observer  the observer to register
     * @param eventType the type of events to observe
     * @param jobType   the job type to filter by (null for all job types)
     * @return a subscription handle that can be used to unsubscribe
     */
    public <T extends JobEvent> MessageBus.Subscription subscribe(JobObserver<T> observer, Class<T> eventType, Class<? extends Job> jobType) {
        ensureStarted();
        return messageBus.subscribe(jobType, eventType, observer);
    }

    /**
     * Subscribes an observer to all events from a specific workflow.
     * This is a convenience method that creates a filtered observer for workflow events.
     *
     * @param workflowId the workflow ID to observe
     * @param observer   the observer to handle workflow events
     * @return a subscription handle that can be used to unsubscribe
     */
    public MessageBus.Subscription observeWorkflow(String workflowId, JobObserver<JobEvent> observer) {
        ensureStarted();
        if (workflowId == null) {
            throw new IllegalArgumentException("Workflow ID cannot be null");
        }

        // Create a filtering observer that only accepts events from this workflow
        JobObserver<JobEvent> workflowObserver = new JobObserver<JobEvent>() {
            @Override
            public Predicate<JobEvent> getPredicate() {
                return event -> {
                    JobSnapshot snapshot = event.snapshot();
                    return snapshot != null && snapshot.getWorkflowId() != null && workflowId.equals(snapshot.getWorkflowId());
                };
            }

            @Override
            public void observe(JobEvent event) {
                observer.observe(event);
            }

            @Override
            public boolean isStale() {
                // Delegate staleness check to the wrapped observer
                return observer.isStale();
            }
        };

        return messageBus.subscribe(null, JobEvent.class, workflowObserver);
    }

    /**
     * Subscribes an AbstractWorkflowObserver to its workflow's events.
     * This method automatically sets the subscription on the observer for self-cleanup.
     *
     * @param <T>       the event type the observer handles
     * @param observer  the workflow observer to subscribe
     * @param eventType the class of events to observe
     * @return a subscription handle that can be used to unsubscribe
     */
    public <T extends JobEvent> MessageBus.Subscription observeWorkflow(AbstractWorkflowObserver<T> observer, Class<T> eventType) {
        ensureStarted();

        MessageBus.Subscription subscription = messageBus.subscribe(null, eventType, observer);
        observer.setSubscription(subscription);

        return subscription;
    }

    /**
     * Cancels a job; {@link JobHandle#cancel(String)} calls this. A job still in a queue
     * leaves it, is recorded CANCELLED, and its waiters are released with a
     * {@link JobCancelledException}. A running job is signalled through its context, and its
     * own unwinding settles the state, the event and the handle. A caller waiting on the
     * handle gets an ExecutionException whose cause is JobCancelledException.
     *
     * @param jobId  the ID of the job to cancel
     * @param reason the cancellation reason
     * @return true if the job was found and cancellation was initiated
     */
    public boolean cancel(String jobId, String reason) {
        // Try to remove from queues first
        QueuedJob<?> removedJob = null;

        // Check standard queue
        for (QueuedJob<?> queuedJob : standardQueue) {
            if (queuedJob.getJob().getId().equals(jobId)) {
                if (standardQueue.remove(queuedJob)) {
                    removedJob = queuedJob;
                    break;
                }
            }
        }

        // Check fast queue if not found
        if (removedJob == null) {
            for (QueuedJob<?> queuedJob : fastQueue) {
                if (queuedJob.getJob().getId().equals(jobId)) {
                    if (fastQueue.remove(queuedJob)) {
                        removedJob = queuedJob;
                        break;
                    }
                }
            }
        }

        // If removed from queue, publish cancellation event
        if (removedJob != null) {
            settleCancelledWhileQueued(removedJob, "Cancelled while queued: " + reason);
            return true;
        }

        // Check running jobs
        for (JobHandle<?> handle : runningJobs) {
            if (handle.getContext().getJobId().equals(jobId)) {
                // Signal cancellation through context
                JobContext<?> context = handle.getContext();
                if (context != null) {
                    context.cancel(reason);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Cancels all jobs belonging to a workflow.
     *
     * <p>This method cancels all running and queued jobs that share the specified workflowId.
     * Useful for fail-fast scenarios where one job failure should abort the entire workflow.
     *
     * @param workflowId the workflow ID to cancel
     * @param reason     the cancellation reason
     * @return the number of jobs cancelled
     */
    public int cancelWorkflow(String workflowId, String reason) {
        if (workflowId == null) {
            return 0;
        }
        int cancelled = 0;

        // Cancel running jobs with matching workflowId
        // Create a snapshot to avoid ConcurrentModificationException
        for (JobHandle<?> handle : new java.util.ArrayList<>(runningJobs)) {
            JobContext<?> context = handle.getContext();
            if (context != null && workflowId.equals(context.getWorkflowId())) {
                context.cancel(reason);
                cancelled++;
            }
        }

        // Remove queued jobs from standard queue
        cancelled += cancelQueuedByWorkflow(standardQueue, workflowId, reason);

        // Remove queued jobs from fast queue
        cancelled += cancelQueuedByWorkflow(fastQueue, workflowId, reason);

        if (cancelled > 0) {
            log.info("Cancelled {} jobs in workflow {}: {}", cancelled, workflowId, reason);
        }
        return cancelled;
    }

    /**
     * Helper to cancel queued jobs by workflowId from a queue.
     */
    private int cancelQueuedByWorkflow(java.util.Queue<QueuedJob<?>> queue, String workflowId, String reason) {
        int cancelled = 0;
        java.util.List<QueuedJob<?>> toRemove = new java.util.ArrayList<>();

        // Create a snapshot to avoid ConcurrentModificationException
        for (QueuedJob<?> queuedJob : new java.util.ArrayList<>(queue)) {
            if (workflowId.equals(queuedJob.getJob().getWorkflowId())) {
                toRemove.add(queuedJob);
            }
        }
        for (QueuedJob<?> queuedJob : toRemove) {
            if (queue.remove(queuedJob)) {
                cancelled++;
                settleCancelledWhileQueued(queuedJob, "Workflow cancelled: " + reason);
            }
        }
        return cancelled;
    }

    /**
     * A job taken out of a queue never runs, so nothing downstream will settle it: the state,
     * the terminal event and the handle are settled here, and whoever waits on the handle
     * wakes with the same JobCancelledException a running job's cancellation produces.
     */
    void settleCancelledWhileQueued(QueuedJob<?> queuedJob, String message) {
        JobContext<?> context = queuedJob.getContext();
        if (context != null) {
            context.setState(JobState.CANCELLED);
            publishEvent(new JobCancelled(context.getSnapshot(), false, message, 0, context.getLlmResponses(), context.getAllMetadata()));
            // a root's workflow ends here as on every other terminal path, so workflow-scoped
            // observers and parents close instead of waiting on a workflow nothing will finish
            publishWorkflowFailedIfRoot(context);
        }
        JobHandle<?> handle = queuedJob.getJobHandle();
        if (handle != null) {
            handle.fail(new JobCancelledException(new JobContext.CancellationException(message)));
        }
    }

    /**
     * Gets statistics for the unified execution system.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("running", state.get() == State.STARTED);
        stats.put("standardQueueSize", standardQueue.size());
        stats.put("fastQueueSize", fastQueue.size());
        stats.put("admissionQueueSize", admission == null ? 0 : admission.queueSize());
        stats.put("activeJobs", runningJobs.size());
        stats.put("shuttingDown", shuttingDown.get());
        return stats;
    }

    /**
     * Scheduler configuration.
     */
    public record SchedulerConfig(long schedulerLoopDelayMillis, long shutdownTimeoutMillis) {
        public static SchedulerConfig defaults() {
            return new SchedulerConfig(100, 30000);  // 100ms loop, 30s shutdown
        }
    }

    /**
     * Publishes an event to the message bus.
     *
     * @param event the event to publish
     */
    void publishEvent(JobEvent event) {
        try {
            messageBus.publish(event);
        }
        catch (Exception e) {
            log.error("Failed to publish event: {}", e.getMessage());
        }
    }

    /**
     * Unified job execution - single path for all jobs regardless of strategy.
     * <p>
     * This method orchestrates the complete job lifecycle:
     * <ol>
     *   <li><b>Phase 1: Dependency Resolution</b> - Wait for all dependencies to complete,
     *       extract their results and final snapshots, and pass to job via {@link JobContext#setDependencyResults(Map)}</li>
     *   <li><b>Phase 2: Job Execution</b> - Execute the job with retry logic, resource management,
     *       timeout enforcement, and transaction management</li>
     * </ol>
     * <p>
     * <b>Dependency Handling:</b>
     * <ul>
     *   <li>Dispatcher blocks on {@code handle.get()} for each dependency (safe - no resources held yet)</li>
     *   <li>Extracts result + final {@link JobSnapshot} for each completed dependency</li>
     *   <li>Stores in {@code Map<JobSnapshot, Object>} and passes to job context</li>
     *   <li>Failed dependencies get {@code null} results if {@code toleratesDependencyFailures=true}</li>
     *   <li>Job accesses pre-computed results via {@code context.getDependencyResults()} - no blocking needed</li>
     * </ul>
     * <p>
     * This design prevents deadlock scenarios where jobs with resources attempt to block on
     * dependency completion. The dispatcher handles all waiting before resource allocation.
     *
     * @param queuedJob the queued job wrapper containing job, handle, and context
     * @param <T>       the job result type
     */
    private <T> void executeJobUnified(QueuedJob<T> queuedJob) {
        Job<T> job = queuedJob.getJob();
        JobHandle<T> handle = queuedJob.getJobHandle();
        JobContext<T> context = queuedJob.getContext();
        String jobId = job.getId();

        // Track as running
        runningJobs.add(handle);
        // The executing-job marker covers the WHOLE execution, orchestrators included:
        // the submission door reads it to identify the calling job of every submit.
        context.markJobExecuting();
        T outcome = null;
        Exception failure = null;
        try {
            // Phase 1: Wait for dependencies and extract results
            Collection<JobHandle<?>> dependencies = context.getDependencies();
            if (dependencies != null && !dependencies.isEmpty()) {
                Map<JobSnapshot, Object> dependencyResults = new LinkedHashMap<>();
                Map<JobSnapshot, Throwable> dependencyErrors = new LinkedHashMap<>();
                List<String> dependencyJobIds = new ArrayList<>();

                for (JobHandle<?> dep : dependencies) {
                    // Extract dependency job ID for DAG reconstruction
                    dependencyJobIds.add(dep.getContext().getJobId());

                    try {
                        Object result = dep.get();  // Dispatcher blocks here (before job executes)
                        JobSnapshot snapshot = dep.getContext().getSnapshot();  // Final snapshot
                        dependencyResults.put(snapshot, result);
                    }
                    catch (Exception e) {
                        log.info("Dependency {} failed for tolerant job {}", dep.getContext().getJobId(), jobId);
                        JobRequirements depReq = queuedJob.getRequirements();
                        if (depReq == null || !depReq.toleratesDependencyFailures()) {
                            throw new DependencyFailedException("Dependency " + dep.getContext().getJobId() + " failed", dep.getContext().getJobId(), e);
                        }

                        // Tolerant jobs get null result and exception for failed dependencies
                        JobSnapshot snapshot = dep.getContext().getSnapshot();
                        dependencyResults.put(snapshot, null);
                        dependencyErrors.put(snapshot, e);
                    }
                }

                // Store results and errors in context for job to access
                context.setDependencyResults(dependencyResults);
                context.setDependencyErrors(dependencyErrors);

                // Update snapshot with dependency job IDs for DAG reconstruction
                context.setDependencyJobIds(dependencyJobIds);
            }

            // Input-side guardrails run here because the thread holds no resources yet
            // (same safety argument as Phase 1) and a refusal must precede allocation.
            GuardrailEnforcer.enforceInput(job, context);

            // Phase 2: Execute job with retry logic
            // (Rate limiter tokens acquired in JobResources constructor)
            outcome = executeWithRetry(queuedJob, job, context);

        }
        catch (DependencyFailedException e) {
            // gh-14. The first terminal transition wins: a cancel that landed while this job waited on
            // its dependency already made it CANCELLED, and the outcome its caller was promised
            // is then the cancellation, settled the way a job cancelled in a queue is.
            context.fail(e);
            if (context.getState() == JobState.CANCELLED) {
                String message = "Job " + jobId + " was cancelled while it waited for its dependencies";
                publishEvent(new JobCancelled(context.getSnapshot(), false, message, 0, context.getLlmResponses(), context.getAllMetadata()));
                publishWorkflowFailedIfRoot(context);
                failure = new JobCancelledException(new JobContext.CancellationException(message));
            }
            else {
                publishEvent(new DependencyFailureEvent(context.getSnapshot(), e));
                publishWorkflowFailedIfRoot(context);
                failure = e;
            }
        }
        catch (Exception e) {
            publishWorkflowFailedIfRoot(context);
            failure = e;
        }
        finally {
            context.clearJobExecuting();
            liveJobs.remove(context.getJobId());
            runningJobs.remove(handle);
            // Clean up any conversations owned by this job
            try {
                ai.redouble.nucleo.harness.conversation.ConversationService.getInstance().releaseAllForJob(jobId);
            }
            catch (Exception e) {
                log.warn("Failed to release conversations for job {}: {}", jobId, e.getMessage());
            }
            // Release the accumulated LLM responses
            context.clearCompletionData();
        }
        // The handle completes last, after the terminal events and after the teardown above:
        // a caller woken by handle.get() finds the job finished - no longer running, its
        // conversations released, its recorded calls released - never a job still being torn
        // down on this thread. A finished job's calls are the ones its terminal event carries.
        if (failure != null) {
            handle.fail(failure);
        }
        else {
            handle.complete(outcome);
        }
    }

    /**
     * Publishes a WorkflowCompleteEvent(failed) if this job is the workflow root.
     * Called from executeJobUnified catch blocks so ALL failure paths produce a terminal signal.
     */
    private <T> void publishWorkflowFailedIfRoot(JobContext<T> context) {
        JobSnapshot snapshot = context.getSnapshot();
        String parentId = snapshot.getParentJobId();
        if (parentId == null || parentId.equals(snapshot.getWorkflowId())) {
            String reason = "Workflow '" + snapshot.getDisplayName() + "' could not be completed";
            publishEvent(new WorkflowCompleteEvent(snapshot, false, reason));
        }
    }

    /**
     * Executes a job with retry logic and timeout enforcement, publishing its terminal event.
     * Returns the result or throws the failure; it never completes the handle, which
     * {@link #executeJobUnified} does once the job is torn down.
     */
    private <T> T executeWithRetry(QueuedJob<T> queuedJob, Job<T> job, JobContext<T> context) throws Exception {
        String jobId = job.getId();
        String userId = context.getUserId();
        Duration timeout = job.getTimeout();

        // Track state across attempts
        Exception lastError = null;
        boolean timedOut = false;
        int failedAttempt = 0;

        int rateLimitAttempt = 0;
        // The first truncation of this execution, once escalated; a second one is judged against it
        OutputTruncationRetryException escalated = null;
        // Upstream failures of this execution, as (resolved spec, signal) pairs. Fed into
        // each re-attempt's resolution situation so a picker can fail over on history.
        List<Situation.Attempt> upstreamAttempts = new ArrayList<>();
        int correctionRetryAttempt = 0;
        for (int attempt = 1; attempt <= 1; attempt++) {
            AtomicBoolean attemptComplete = new AtomicBoolean(false);
            AtomicBoolean attemptTimedOut = new AtomicBoolean(false);
            AtomicBoolean resourcesClosed = new AtomicBoolean(false);  // Double-close protection
            JobResources resources = null;
            JobRequirements attemptRequirements = null;
            T result;
            ScheduledFuture<?> timeoutKiller = null;
            AtomicReference<ScheduledFuture<?>> phase2Killer = new AtomicReference<>();

            try {
                // Check for cancellation
                if (context.getState() == JobState.CANCELLED) {
                    lastError = new JobContext.CancellationException("Job " + jobId + " was cancelled before it started");
                    break;
                }

                // The one requirements capture of this attempt - a fresh mint of the job's
                // model bindings. It is threaded explicitly through resolution, resources,
                // the transaction branch, and the rate-limiter signals; nothing below
                // re-invokes getRequirements().
                attemptRequirements = job.getRequirements();

                // The single model-resolution point: picker + gate + pricing, per binding,
                // BEFORE any resource is acquired - the consult always runs resource-free.
                resolveModelBindings(job, attemptRequirements, context, upstreamAttempts);

                // Create resources for this attempt
                resources = createJobResources(attemptRequirements, context);

                // Two-phase timeout enforcement: cooperative then aggressive
                final JobResources finalResources = resources;
                if (timeout != null) {
                    Duration gracePeriod = calculateGracePeriod(timeout);

                    // Phase 1: Cooperative - signal cancellation and close resources
                    timeoutKiller = timeoutExecutor.schedule(() -> {
                        if (!attemptComplete.get()) {
                            attemptTimedOut.set(true);
                            log.info("Timeout for job {}, requesting cooperative shutdown", jobId);
                            context.signalTimeout();

                            // Phase 2: Aggressive - interrupt thread and abort connections. Armed
                            // BEFORE the cooperative close below, which may itself be the thing
                            // that never returns; stored so it can be cancelled on completion.
                            ScheduledFuture<?> phase2 = timeoutExecutor.schedule(() -> {
                                if (!attemptComplete.get()) {
                                    log.warn("Job {} ignored timeout after {}ms grace, forcing termination", jobId, gracePeriod.toMillis());

                                    Future<?> executionFuture = queuedJob.getExecutionFuture();
                                    if (executionFuture != null && !executionFuture.isDone()) {
                                        executionFuture.cancel(true);
                                    }

                                    // Aggressive revocation of a cooperative close that did not finish.
                                    // forceClose is safe after and during close: the handle container is
                                    // immutable, handles carry their own flags, the grant settles once.
                                    try {
                                        if (finalResources != null) {
                                            finalResources.forceClose();
                                        }
                                    }
                                    catch (Exception e) {
                                        log.error("Error during forced termination: {}", e.getMessage());
                                    }
                                }
                            }, gracePeriod.toMillis(), TimeUnit.MILLISECONDS);
                            phase2Killer.set(phase2);

                            // Cooperative close on its own virtual thread: a close that blocks must
                            // never occupy a timeout-enforcer thread, or it would starve the phase 2
                            // of every other job. Atomically claim the right to close resources.
                            if (resourcesClosed.compareAndSet(false, true)) {
                                Thread.ofVirtual().name("cooperative-close-" + jobId).start(() -> {
                                    try {
                                        finalResources.close();
                                    }
                                    catch (Exception e) {
                                        log.debug("Error during cooperative close: {}", e.getMessage());
                                    }
                                });
                            }
                        }
                    }, timeout.toMillis(), TimeUnit.MILLISECONDS);
                }

                log.debug("Executing {} (standard queue {}, fast queue {}, running {})", context.getJobId(), standardQueue.size(), fastQueue.size(), runningJobs.size());

                // Pre-execute hook, once per attempt: after resources are allocated, before the
                // started event and execute(), as Job.preExecute promises
                job.preExecute(context);
                context.setState(JobState.RUNNING);
                publishEvent(new JobStartedEvent(context.getSnapshot(), attempt));

                // Track execution timing
                Instant startTime = Instant.now();

                // Execute with optional transaction management
                JobRequirements txReq = attemptRequirements;
                if (txReq != null && txReq.requiresDatabase() && txReq.requiresTransaction()) {
                    resources.beginAll();
                    try {
                        result = job.execute(resources, context);
                        resources.commitAll();
                    }
                    catch (Exception e) {
                        // Only attempt rollback if resources weren't closed by timeout
                        if (!resourcesClosed.get()) {
                            try {
                                resources.rollbackAll();
                            }
                            catch (Exception rollbackError) {
                                // The job's own exception is the story; a failed cleanup
                                // rollback is its consequence, never its replacement. A
                                // dead connection fails the work AND the rollback - the
                                // rollback failure rides as suppressed, the cause
                                // propagates with its true type and classification. The
                                // cause is logged FIRST so the reader meets the story
                                // before its consequence.
                                log.error("Job {} failed; the cleanup rollback below is a consequence of this failure", jobId, e);
                                log.error("Cleanup rollback failed for job {} as a consequence of the failure above", jobId, rollbackError);
                                context.publishUserNotification(UserNotificationEvent.Severity.ERROR, "Transaction Rollback Failed", "The job failed (" + e.getMessage() + ") and the cleanup rollback then also failed as a consequence - typically both from one broken connection, in which case the database has already discarded the uncommitted work. A rollback failure on a live connection leaves state to verify.");
                                e.addSuppressed(rollbackError);
                            }
                        }
                        throw e;
                    }
                }
                else {
                    result = job.execute(resources, context);
                }

                // Signal rate limiters about success BEFORE the finally block releases
                // permits. Otherwise close() -> release() would hit the probe still in
                // PROBING state, see no outcome signal yet, and self-heal revert to BLOCKED
                // - losing the PROBING -> HEALTHY transition that onSuccess would perform.
                signalRateLimiterSuccess(attemptRequirements);

                // JobCompletedEvent below handles the completion notification

                // Calculate duration
                long duration = Duration.between(startTime, Instant.now()).toMillis();

            }
            catch (UpstreamRetryException e) {
                // One transparent-retry loop for every upstream signal - it does not count
                // against the job's retry policy, the signal itself carries its pacing (a
                // 529 waits in a wider window than a 429 or a 5xx), and the job sets how many
                // re-runs the one shared counter allows. Only the log line and the event stay
                // per-signal, so diagnostics keep saying what happened.
                int budget = job.getUpstreamRetries();
                if (rateLimitAttempt >= budget) {
                    // The upstream never recovered within the budget: nothing the model can correct
                    lastError = new UncorrectableRuntimeLLMException("Exceeded the transparent retry budget ("
                            + budget + ") on " + e.getClass().getSimpleName() + ": " + UpstreamRetryException.details(e), e);
                    failedAttempt = attempt;
                    break;
                }
                if (attemptRequirements != null) {
                    for (ModelBinding failedBinding : attemptRequirements.getModelBindings()) {
                        if (failedBinding.isResolved()) {
                            upstreamAttempts.add(new Situation.Attempt(failedBinding.getModel(), e));
                        }
                    }
                }
                int attemptFactor = Math.min(rateLimitAttempt + 1, 10);
                long minJitterMs = e.baseMinJitterMs() * attemptFactor;
                long maxJitterMs = Math.min(e.baseMaxJitterMs() * attemptFactor, 300000);
                long jitterMs = ThreadLocalRandom.current().nextLong(minJitterMs, maxJitterMs);
                Duration estimatedDelay = Duration.ofMillis(jitterMs);
                switch (e) {
                    case RateLimitRetryException rateLimit -> {
                        log.info("Rate limit hit for {}, transparent-retry attempt {}", jobId, rateLimitAttempt);
                        RateLimitInfo info = rateLimit.getRateLimitInfo();
                        String modelName = info != null ? info.getModel() : "unknown";
                        double throttle = 0.0;
                        if (info != null && info.getModel() != null) {
                            ModelSpec modelInfo = Models.findSpec(info.getModel());
                            if (modelInfo != null) {
                                TokenBucketRateLimiter limiter = RateLimiterRegistry.getInstance().getRateLimiter(modelInfo);
                                if (limiter != null) {
                                    throttle = limiter.getThrottleCoefficient();
                                }
                            }
                        }
                        publishEvent(new RateLimitRetryEvent(context.getSnapshot(),
                                info != null ? info.getType() : RateLimitType.UNKNOWN, modelName, rateLimitAttempt + 1, throttle, estimatedDelay));
                    }
                    case OverloadRetryException overload -> {
                        log.warn("Upstream overloaded for {} from {}: {}", jobId, overload.getProvider(), overload.getErrorDetails());
                        publishEvent(new TransientErrorRetryEvent(context.getSnapshot(),
                                overload.getProvider(), overload.getErrorDetails(), rateLimitAttempt + 1, estimatedDelay));
                    }
                    case TransientErrorRetryException transientError -> {
                        log.warn("Server error for {} from {}: {}", jobId, transientError.getProvider(), transientError.getErrorDetails());
                        publishEvent(new TransientErrorRetryEvent(context.getSnapshot(),
                                transientError.getProvider(), transientError.getErrorDetails(), rateLimitAttempt + 1, estimatedDelay));
                    }
                }
                // Pace holding nothing: disarm both killers, mark the attempt complete and close
                // its resources before the delay. The next iteration re-admits with a fresh
                // JobResources. A phase-1 killer that already fired has cancelled the context,
                // so that attempt is a timeout, never a retry.
                if (timeoutKiller != null && !timeoutKiller.cancel(false) && attemptTimedOut.get()) {
                    lastError = e;
                    timedOut = true;
                    failedAttempt = attempt;
                    break;
                }
                timeoutKiller = null;
                ScheduledFuture<?> pacingPhase2 = phase2Killer.getAndSet(null);
                if (pacingPhase2 != null) {
                    pacingPhase2.cancel(false);
                }
                attemptComplete.set(true);
                if (resources != null && resourcesClosed.compareAndSet(false, true)) {
                    try {
                        resources.close();
                    }
                    catch (Exception closeFailure) {
                        log.error("Exception during resources.close() before pacing {}: {} - {}",
                                jobId,
                                closeFailure.getClass().getSimpleName(),
                                closeFailure.getMessage());
                        log.error(closeFailure.getMessage(), closeFailure);
                    }
                }
                try {
                    Thread.sleep(jitterMs);
                }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    lastError = ie;
                    failedAttempt = attempt;
                    break;
                }
                rateLimitAttempt++;
                attempt--; // don't consume a regular retry attempt
                continue;  // re-enter the for loop to re-execute the job
            }
            catch (OutputTruncationRetryException e) {
                // Deterministic one-shot retry: the client has already bumped the sent
                // message's requestedOutputTokens to the model ceiling (see
                // AbstractLLMClient.throwIfTruncated), so a job that retains its
                // conversation re-runs getRequirements/JobResources with the escalated
                // budget and the SDK sends the new max_tokens. A call that truncates AT
                // the ceiling never reaches here - the client surfaces it as
                // UncorrectableRuntimeLLMException - so a second retry signal is one of
                // two things, and the message says which: the job rebuilt its
                // conversation per attempt and re-issued the request at its declared
                // budget, so the escalation never reached the wire; or picker failover
                // moved the attempt to another model, below whose ceiling it truncated.
                if (escalated != null) {
                    String outcome;
                    if (!e.getModelName().equals(escalated.getModelName())) {
                        outcome = "Output truncated on " + e.getModelName() + " at " + e.getPreviousBudget()
                                + " after failover from " + escalated.getModelName() + " - the answer does not fit the new model either";
                    }
                    else {
                        outcome = "Output truncated again at " + e.getPreviousBudget() + " tokens on " + e.getModelName()
                                + ": the job rebuilt its conversation per attempt, so the escalation to "
                                + escalated.getNewBudget() + " never reached the wire; its declared budget is its ceiling";
                    }
                    lastError = new UncorrectableRuntimeLLMException(outcome, e);
                    failedAttempt = attempt;
                    break;
                }
                log.warn("Output truncation on {} ({}): previous budget {}, retrying with {}", jobId, e.getModelName(), e.getPreviousBudget(), e.getNewBudget());
                publishEvent(new OutputTruncationRetryEvent(context.getSnapshot(),
                        e.getModelName(), e.getPreviousBudget(), e.getNewBudget()));
                escalated = e;
                attempt--;
                continue;
            }
            catch (ResponseCorrectionRetryException e) {
                // Deterministic retry: the job has already appended a correction
                // message to its conversation (see LLMCall.execute), so re-entering
                // the loop re-runs getRequirements/JobResources against the grown
                // conversation and the model gets a chance to fix its output. The
                // job tracks its own correction budget and stops throwing once it
                // is exhausted; this cap is only a backstop against runaway jobs.
                if (correctionRetryAttempt >= MAX_CORRECTION_RETRIES) {
                    // A job still asking for corrections past the backstop is looping, not converging
                    lastError = new UncorrectableRuntimeLLMException(
                        "Exceeded max response correction retries (" + MAX_CORRECTION_RETRIES + ")", e);
                    failedAttempt = attempt;
                    break;
                }
                Throwable cause = e.getCause();
                String failureSummary = cause != null ? cause.getClass().getSimpleName() : "unknown failure";
                log.warn("Response correction on {} ({}): {}, correction attempt {}", jobId, e.getModelName(), failureSummary, e.getCorrectionAttempt());
                publishEvent(new ResponseCorrectionRetryEvent(context.getSnapshot(),
                        e.getModelName(), e.getCorrectionAttempt(), failureSummary));
                correctionRetryAttempt++;
                attempt--;
                continue;
            }
            catch (CancellationException | JobContext.CancellationException e) {
                // A cancellation is never retried and never charged to a rate limiter: the
                // runtime's own signal, or the JDK's from a future the job was waiting on
                lastError = e;
                failedAttempt = attempt;
                break;
            }
            catch (Exception e) {
                if (attemptTimedOut.get()) {
                    // Timeout - resources were forcibly closed, keep original exception
                    lastError = e;
                    timedOut = true;
                    failedAttempt = attempt;
                    break;
                }

                // Signal custom rate limiters about upstream failure so they throttle / open circuits.
                if (e instanceof ExternalServiceException ese) {
                    signalRateLimiterFailure(attemptRequirements, UpstreamFailure.from(ese));
                }

                lastError = e;

                failedAttempt = attempt;
                break;
            }
            catch (Throwable t) {
                // Catch Error types (OutOfMemoryError, NoClassDefFoundError, etc.)
                log.error("CRITICAL: Job {} threw Throwable: {}", jobId, t.getClass().getName(), t);
                log.error(t.getMessage(), t);

                // An Error is the process's fault, never the model's: a system error of the job
                lastError = new SystemException("job", "Job threw " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
                timedOut = attemptTimedOut.get();
                failedAttempt = attempt;
                break;  // Don't retry on Error/Throwable
            }
            finally {
                // ALWAYS cancel timeouts to prevent them from firing after job completes
                if (timeoutKiller != null) {
                    if (!timeoutKiller.cancel(false)) {
                        log.warn("Phase 1 timeout already fired for {}", jobId);
                    }
                }
                ScheduledFuture<?> phase2 = phase2Killer.get();
                if (phase2 != null) {
                    if (!phase2.cancel(false)) {
                        log.warn("Phase 2 timeout already fired for {}", jobId);
                    }
                }

                // Mark attempt as complete to prevent timeout from firing
                attemptComplete.set(true);

                // Atomic double-close protection: only close if we can claim the right
                if (resources != null && resourcesClosed.compareAndSet(false, true)) {
                    try {
                        resources.close();
                    }
                    catch (Exception e) {
                        log.error("Exception during resources.close() for {}: {} - {}", jobId, e.getClass().getSimpleName(), e.getMessage());
                        log.error(e.getMessage(), e);
                    }
                }
            }
            // AFTER resources released: check if this attempt succeeded
            // A job succeeds if it completes without throwing an exception (exception-based failure model)
            // The result may be null (Void type, or legitimate null return) - that's still success!
            // Note: signalRateLimiterSuccess was already called inside the try block before close(),
            // so rate limiter circuit state has already been transitioned.
            if (lastError == null) {
                // Output-side guardrails run after resources are released and before the
                // result is delivered: a refusal converts the attempt into a failure and
                // the caller never sees the result. For a transactional job the commit is
                // already durable - output guards police information flow, not effects.
                try {
                    GuardrailEnforcer.enforceOutput(job, result, context);
                }
                catch (Exception guardFailure) {
                    lastError = guardFailure;
                }
            }
            if (lastError == null) {
                context.complete(result);
                // A cancel that landed while the job ran, on a job that never checked its token:
                // the state is CANCELLED, the result was promised to nobody, and the outcome is
                // the cancellation. Settle it through the failure path with the runtime's own signal.
                if (context.getState() == JobState.CANCELLED) {
                    lastError = new JobContext.CancellationException(
                            "Job " + jobId + " was cancelled while running and finished without observing it");
                    failedAttempt = attempt;
                }
            }
            if (lastError == null) {
                // Success! Job completed successfully

                // Post-execute hook - runs before terminal event so metadata is available
                try {
                    job.postExecute(context);
                }
                catch (Exception postError) {
                    log.warn("Error in postExecute for job {}: {}", jobId, postError.getMessage());
                }
                publishEvent(new JobCompletedEvent<>(context.getSnapshot(), result, attempt, context.getLlmResponses(), context.getAllMetadata()));

                // Publish workflow terminal event for root jobs
                String parentId = context.getSnapshot().getParentJobId();
                if (parentId == null || parentId.equals(context.getSnapshot().getWorkflowId())) {
                    publishEvent(new WorkflowCompleteEvent(context.getSnapshot()));
                }

                // The handle is completed by executeJobUnified once the job is torn down
                return result;
            }
        }
        // Post-execute hook - runs before terminal event so metadata is available
        try {
            job.postExecute(context);
        }
        catch (Exception postError) {
            log.warn("Error in postExecute for job {}: {}", jobId, postError.getMessage());
        }

        // Determine and set failure state, publish technical event
        // User-facing terminal event (WorkflowCompleteEvent) is published by executeJobUnified for root jobs
        // The runtime's own cancellations are JobContext.CancellationException throughout; the
        // JDK's is accepted too, for a job that was waiting on a future somebody cancelled.
        // gh-14. The first terminal transition wins: an external cancel that landed while the job ran
        // made it CANCELLED already, and whatever the job did afterwards - failed with an error
        // of its own, ran into its timeout - the outcome its caller was promised is the cancel.
        boolean cancelledFirst = context.getState() == JobState.CANCELLED;
        boolean wasCancelled = cancelledFirst
                || lastError instanceof java.util.concurrent.CancellationException
                || lastError instanceof JobContext.CancellationException;
        timedOut = timedOut && !cancelledFirst;
        if (timedOut) {
            context.setState(JobState.TIMED_OUT);
            publishEvent(new JobTimedOut(context.getSnapshot(), timeout, failedAttempt, context.getLlmResponses(), context.getAllMetadata()));
        }
        else if (wasCancelled) {
            // An external cancel (dispatcher.cancel / cancelWorkflow) sets CANCELLED on the still-running
            // job before it unwinds here, so the state is already terminal; only set it when it isn't.
            if (!context.getState().isTerminal()) {
                context.setState(JobState.CANCELLED);
            }
            publishEvent(new JobCancelled(context.getSnapshot(), false, failedAttempt, context.getLlmResponses(), context.getAllMetadata()));
        }
        else {
            context.setState(JobState.FAILED);
            publishEvent(new JobFailedEvent(context.getSnapshot(), lastError, failedAttempt, context.getLlmResponses(), context.getAllMetadata()));
        }

        // Translate a casualty of our own termination into an honest framework exception so callers
        // and the LLM never see the raw downstream failure (e.g. "Statement closed") of a session we
        // reclaimed. The guard skips already-canonical exceptions that bubbled up from a child level.
        // Timeout is checked first: a cancel-shaped exception arriving on a timed-out job is a timeout.
        if (lastError != null) {
            if (timedOut && !(lastError instanceof LLMReadable)) {
                throw new JobTimeoutException(timeout, lastError);
            }
            // The canonical wrap always applies to a cancelled job. Its cause is what the job
            // unwound with: the cancellation message itself when the job observed its token,
            // or the error of its own a job that ignored the token ended on after the cancel.
            if (wasCancelled) {
                throw new JobCancelledException(lastError);
            }
            throw lastError;
        }
        else {
            throw new SystemException("JobDispatcher", "Job " + jobId + " failed with no exception captured", null);
        }
    }

    /**
     * Signals all custom rate limiters attached to this job that the request failed,
     * so they can throttle or open their circuit breakers. Each limiter is signaled
     * with the same input it was acquired with, so multi-bucket limiters route the
     * failure to the correct bucket.
     */
    private void signalRateLimiterFailure(JobRequirements req, UpstreamFailure failure) {
        if (req == null || !req.requiresCustomRateLimiters()) {
            return;
        }
        for (Map.Entry<RateLimiter<?>, Object> entry : req.getCustomRateLimiters().entrySet()) {
            try {
                signalFailure(entry.getKey(), entry.getValue(), failure);
            }
            catch (Exception ex) {
                log.warn("Failed to signal rate limit error on {} ({}): {}", entry.getKey().getClass().getSimpleName(), failure.summary(), ex.getMessage());
            }
        }
    }

    /**
     * Signals all custom rate limiters attached to this job that the request succeeded,
     * so they gradually recover their throttle state and close any probing circuits.
     * Each limiter is signaled with the same input it was acquired with, so multi-bucket
     * limiters apply the recovery to the correct bucket.
     */
    private void signalRateLimiterSuccess(JobRequirements req) {
        if (req == null || !req.requiresCustomRateLimiters()) {
            return;
        }
        for (Map.Entry<RateLimiter<?>, Object> entry : req.getCustomRateLimiters().entrySet()) {
            try {
                signalSuccess(entry.getKey(), entry.getValue());
            }
            catch (Exception ex) {
                log.warn("Failed to signal rate limit success on {}: {}", entry.getKey().getClass().getSimpleName(), ex.getMessage());
            }
        }
    }

    /**
     * The custom limiter map pairs each limiter with the input it was acquired with, so the
     * value is the limiter's own {@code T}; the cast states that pairing where the wildcard
     * cannot.
     */
    @SuppressWarnings("unchecked")
    private static <T> void signalFailure(RateLimiter<T> limiter, Object input, UpstreamFailure failure) {
        limiter.onRateLimitError(failure, (T) input);
    }

    @SuppressWarnings("unchecked")
    private static <T> void signalSuccess(RateLimiter<T> limiter, Object input) {
        limiter.onSuccess((T) input);
    }

    /**
     * The grace period between the cooperative and the aggressive termination phases: ten
     * percent of the timeout, never under 500 milliseconds and never over 30 seconds.
     * Package-private so the package's tests pin the numbers.
     *
     * @param timeout job's maximum execution time
     * @return grace period duration
     */
    static Duration calculateGracePeriod(Duration timeout) {
        long minGrace = 500;
        long proportionalGrace = (long)(timeout.toMillis() * 0.10);
        long maxGrace = 30_000;

        return Duration.ofMillis(Math.min(maxGrace, Math.max(minGrace, proportionalGrace)));
    }

    /**
     * Validates that adding dependencies won't create a cycle.
     * Uses DFS to detect cycles in the dependency graph.
     *
     * @param jobId        the job ID being submitted
     * @param jobClass     the job class name
     * @param dependencies the dependencies to validate
     * @throws IllegalArgumentException if a cycle is detected
     */
    private void validateNoCycles(String jobId, String jobClass, Collection<? extends JobHandle<?>> dependencies) {
        Set<String> visited = new HashSet<>();
        Deque<CycleNode> path = new ArrayDeque<>();
        path.addLast(new CycleNode(jobId, jobClass));
        detectCycleDFS(jobId, dependencies, visited, path);
    }

    /**
     * DFS traversal to detect cycles in dependency graph.
     *
     * @param currentJobId current job being processed
     * @param deps         dependencies to check
     * @param visited      set of visited job IDs
     * @param path         current path from root
     */
    private void detectCycleDFS(String currentJobId, Collection<? extends JobHandle<?>> deps, Set<String> visited, Deque<CycleNode> path) {
        visited.add(currentJobId);
        if (deps != null) {
            for (JobHandle<?> dep : deps) {
                String depId = dep.getContext().getJobId();
                String depClass = dep.getContext().getJob().getClass().getSimpleName();
                if (pathContains(path, depId)) {
                    throwCycleException(path, depId, depClass);
                }
                if (!visited.contains(depId)) {
                    path.addLast(new CycleNode(depId, depClass));
                    detectCycleDFS(depId, dep.getContext().getDependencies(), visited, path);
                    path.removeLast();
                }
            }
        }
    }

    /**
     * Checks if a job ID is already in the current path.
     */
    private boolean pathContains(Deque<CycleNode> path, String jobId) {
        for (CycleNode node : path) {
            if (node.jobId.equals(jobId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Throws exception with detailed cycle information.
     *
     * @param path          the current path
     * @param cycleJobId    the job ID where cycle was detected
     * @param cycleJobClass the job class where cycle was detected
     */
    private void throwCycleException(Deque<CycleNode> path, String cycleJobId, String cycleJobClass) {
        StringBuilder msg = new StringBuilder("Cycle detected in job dependencies:\n");
        boolean inCycle = false;
        for (CycleNode node : path) {
            if (node.jobId.equals(cycleJobId)) {
                inCycle = true;
            }
            if (inCycle) {
                msg.append("  ").append(node.jobClass).append("[").append(node.jobId).append("] depends on\n");
            }
        }
        msg.append("  ").append(cycleJobClass).append("[").append(cycleJobId).append("] <- CYCLE COMPLETES HERE");
        throw new IllegalArgumentException(msg.toString());
    }

    /**
     * The single model-resolution point: turns each declared binding of this attempt's
     * requirements into a resolved, priced reservation - BEFORE any resource is acquired,
     * so the picker is always consulted from a resource-free place. A transparent-retry
     * re-entry re-runs this with the accumulated attempt history, which is why failover
     * is a pure picker policy with no extra dispatcher machinery.
     */
    private void resolveModelBindings(Job<?> job, JobRequirements requirements, JobContext<?> context, List<Situation.Attempt> upstreamAttempts) {
        if (requirements == null) {
            return;
        }
        List<ModelBinding> bindings = requirements.getModelBindings();
        if (bindings.isEmpty()) {
            return;
        }
        ComplianceEnvelope envelope = getComplianceEnvelope();
        for (ModelBinding binding : bindings) {
            Seat seat = new Seat(job.getClass(), binding.getGrade(), binding.getKind());
            Situation situation = new Situation();
            situation.setEnvelope(envelope);
            situation.setSnapshot(context.getSnapshot());
            if (job instanceof ScopeAuthority authority) {
                situation.setScopeGuard(authority.getScopeGuard());
            }
            if (!upstreamAttempts.isEmpty()) {
                situation.setAttempts(List.copyOf(upstreamAttempts));
            }
            situation.setDepth(binding.getDepth());
            situation.setInteractive(binding.isInteractive());
            situation.setSends(binding.getSends());
            ConversationContext conversation = binding.getConversation();
            if (conversation != null) {
                situation.setInteractive(binding.isInteractive() || conversation.isInteractive());
                situation.setCarried(conversation.carriedInputs());
                if (conversation.getPriorSpecId() != null) {
                    situation.setPrior(Models.findSpec(conversation.getPriorSpecId()));
                }
            }
            // A pinned binding skips only the picker consult - the gate judges it
            // identically. Unpinned embeddings resolve through the frozen corpus
            // declaration, never through provide(): stored vectors are only comparable
            // to vectors from the model that produced them. Unpinned decisions resolve
            // through the deployment's decision declaration: not on the ladder, so no pick.
            binding.resolve(binding.isPinned()
                    ? ModelPickers.resolvePinned(binding.getPinnedSpec(), seat, situation)
                    : switch (binding.getKind()) {
                        case EMBEDDINGS -> ModelPickers.resolveEmbeddings(seat, situation);
                        case DECISION -> ModelPickers.resolveDecision(seat, situation);
                        case LLM -> ModelPickers.resolve(seat, situation);
                    });
            binding.price();
        }
        // the money wall, last: what the attempt would commit is known only now
        for (SpendGate gate : spendGates) {
            gate.admit(context, bindings);
        }
    }

    /**
     * Creates the {@link JobResources} holder for a single execution attempt, acquiring
     * against the attempt's already-resolved requirements capture. Null requirements are
     * mapped to an empty bundle (no database, no transaction).
     */
    private JobResources createJobResources(JobRequirements requirements, JobContext<?> context) {
        return new JobResources(requirements, context, admission);
    }

    /**
     * Internal wrapper for queued jobs with priority support and timeout enforcement.
     * Package-private so the package's tests pin the queue order.
     */
    static class QueuedJob<T> implements Comparable<QueuedJob<?>> {
        private final Job<T> job;
        private JobHandle<T> jobHandle;
        private String userId;
        private JobContext<T> context;
        /** The submit-time getRequirements capture, reused by every pre-execution read. */
        private JobRequirements requirements;
        private final Instant queuedAt = Instant.now();
        private final long submittedAt = System.currentTimeMillis();

        /**
         * Future of the executing thread for timeout-driven cancellation.
         */
        private volatile Future<?> executionFuture;

        QueuedJob(Job<T> job) {
            this.job = job;
        }

        public Instant getQueuedAt() {
            return queuedAt;
        }

        public Job<T> getJob() {
            return job;
        }

        public JobHandle<T> getJobHandle() {
            return jobHandle;
        }

        public void setJobHandle(JobHandle<T> handle) {
            this.jobHandle = handle;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public JobContext<T> getContext() {
            return context;
        }

        public void setContext(JobContext<T> context) {
            this.context = context;
        }

        public JobRequirements getRequirements() {
            return requirements;
        }

        public void setRequirements(JobRequirements requirements) {
            this.requirements = requirements;
        }

        public Future<?> getExecutionFuture() {
            return executionFuture;
        }

        public void setExecutionFuture(Future<?> executionFuture) {
            this.executionFuture = executionFuture;
        }

        @Override
        public int compareTo(QueuedJob<?> other) {
            // The priority queue serves the higher Job.getPriority() first, as that method
            // declares, and equal priorities in submission order
            int priorityCompare = Integer.compare(other.job.getPriority(), job.getPriority());
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return Long.compare(submittedAt, other.submittedAt);
        }
    }


    /**
     * Helper class for tracking dependency path during cycle detection.
     */
    private static class CycleNode {
        final String jobId;
        final String jobClass;

        CycleNode(String jobId, String jobClass) {
            this.jobId = jobId;
            this.jobClass = jobClass;
        }
    }
}