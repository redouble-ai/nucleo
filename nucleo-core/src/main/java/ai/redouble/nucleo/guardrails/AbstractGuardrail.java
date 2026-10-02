/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.schema.*;
import org.slf4j.*;

import java.time.*;

/**
 * Job plumbing shared by all guardrail bases: target and snapshot delivery, resource
 * capture, observability metadata, the final execute-calls-validate wiring.
 * <p>
 * Deliberately does NOT implement {@link Guardrail} - the interface is sealed to its
 * kind branches, and a concrete guardrail picks its kind by extending the matching
 * branch base ({@link AbstractContentGuardrail}, {@link AbstractAuthGuardrail},
 * {@link AbstractAdmissionGuardrail}, {@link AbstractValidationGuardrail}), each of
 * which extends this class and implements its branch interface. The abstract members
 * here carry the same signatures the sealed interface demands, so branch bases satisfy
 * it by inheritance.
 * <p>
 * As a job: type {@link JobType#GUARDRAIL}, a read-only requirement, a ten-second
 * timeout unless the author sets another, and a final {@link #execute} that calls
 * {@link #validate} on the target and returns nothing. Before it runs it records the
 * target's serialized form under {@link #OBS_TARGET}, the gated job's id under
 * {@link #OBS_GATES_JOB_ID} and the rung under {@link #OBS_GATES_PHASE}; after it
 * runs, {@code obs.output} is {@code FAIL} when {@link #validate} threw, else {@code PASS}.
 * <p>
 * A guardrail is a leaf job unless it declares {@link ScopeAuthority}. The scope-guard
 * seat that interface demands lives here so the declaration is the whole opt-in: the
 * door then composes and seals the seat from the guard the gated job was admitted
 * under, exactly as it does for an orchestrator, and every child the guard submits
 * inherits from it. A guard never authors a scope of its own - its binding is always
 * the gated flow's - so the authoring setter refuses. A guard that submits children
 * waits on them and must declare no resources, the rule that governs every job that
 * blocks on a handle.
 *
 * @param <T> the type this guardrail validates
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-13)
 */
public abstract class AbstractGuardrail<T> extends AbstractJob<Void> {
    private static final Logger log = LoggerFactory.getLogger(AbstractGuardrail.class);
    /**
     * Metadata key carrying the {@code job_id} of the job this guardrail gates,
     * taken from the delivered {@link JobSnapshot}. For a deployment's recorder to lift
     * into a column of its own so the
     * relationship between a guardrail row and the gated row is queryable without
     * overloading {@code parent_job_id}; the runtime itself writes only the metadata.
     */
    public static final String OBS_GATES_JOB_ID = "obs.gates_job_id";
    /**
     * Metadata key carrying the gating rung: {@code AUTH}, {@code ADMISSION},
     * {@code CONTENT_INPUT}, {@code CONTENT_OUTPUT} or {@code VALIDATION}.
     */
    public static final String OBS_GATES_PHASE = "obs.gates_phase";
    /** Metadata key for the gated job's input serialization, written by tools; reused here for the target. */
    public static final String OBS_TARGET = "obs.input";
    private T target;
    private JobSnapshot gatedSnapshot;
    private JobResources resources;
    private ScopeGuard scopeGuard;
    // The guard's own record of its verdict: the context never carries the failure
    // (JobContext.fail has no caller), so it cannot tell a guard how it ended
    private boolean validationThrew;

    protected AbstractGuardrail(Identifiable parent) {
        super(parent, null);
        setTimeout(Duration.ofSeconds(10));
    }

    /**
     * Validates the target object. Same contract as {@link Guardrail#validate}; declared
     * here so branch bases inherit the implementation seat.
     */
    public abstract void validate(T target) throws GuardrailException;

    /**
     * The class of targets this guardrail validates - {@link Guardrail#targetType()}.
     * Self-reported by every concrete guardrail; the framework never resolves it
     * reflectively.
     */
    public abstract Class<T> targetType();

    /**
     * The gating rung recorded in {@link #OBS_GATES_PHASE}. Provided by the branch
     * bases; concrete guardrails never touch it.
     */
    protected abstract String gateRungLabel();

    /**
     * Sets the target object this guardrail validates. Called by the enforcing code
     * before submitting the guardrail as a job.
     */
    public void setTarget(T target) {
        this.target = target;
    }

    protected T getTarget() {
        return target;
    }

    /**
     * Delivers the immutable snapshot of the gated job - {@link Guardrail#setGatedSnapshot}.
     */
    public void setGatedSnapshot(JobSnapshot snapshot) {
        this.gatedSnapshot = snapshot;
    }

    /**
     * The immutable snapshot of the gated job: principal ({@code getUserId()}), tool
     * ({@code getJobClass()}), position in execution. Null only when the guardrail is
     * run outside dispatch enforcement (e.g. the Prompts facade, which gates no job).
     */
    protected JobSnapshot getGatedSnapshot() {
        return gatedSnapshot;
    }

    protected JobResources getResources() {
        return resources;
    }

    /**
     * The sealed binding of the flow this guard runs in, consulted by the door only when
     * the concrete guard declares {@link ScopeAuthority} - {@link ScopeAuthority#getScopeGuard()}.
     */
    public ScopeGuard getScopeGuard() {
        return scopeGuard;
    }

    /**
     * A guard's binding is composed by the door from the gated flow; it is never authored.
     */
    public void setScopeGuard(ScopeGuard guard) {
        throw new UnsupportedOperationException(getClass().getSimpleName()
                + ": a guardrail never authors a scope guard - its binding is the gated flow's, sealed at the internal door");
    }

    /**
     * Framework-called at the internal door - {@link ScopeAuthority#sealScopeGuard(ScopeGuard)}.
     */
    public void sealScopeGuard(ScopeGuard effective) {
        this.scopeGuard = effective;
    }

    @Override
    public void preExecute(JobContext<Void> context) {
        try {
            if (target != null) {
                context.putMetadata(OBS_TARGET, NucleoJsonSerializer.write(target));
            }
        }
        catch (Exception e) {
            log.warn("Failed to serialize guardrail target for observability: {}", e.getMessage());
        }
        if (gatedSnapshot != null) {
            context.putMetadata(OBS_GATES_JOB_ID, gatedSnapshot.getJobId());
        }
        context.putMetadata(OBS_GATES_PHASE, gateRungLabel());
    }

    /** Records the verdict as {@code obs.output}: {@code FAIL} when {@link #validate} threw, else {@code PASS}. */
    @Override
    public void postExecute(JobContext<Void> context) {
        context.putMetadata("obs.output", validationThrew ? "FAIL" : "PASS");
    }

    @Override
    public final Void execute(JobResources resources, JobContext<Void> context) throws Exception {
        this.resources = resources;
        try {
            validate(target);
        }
        catch (Exception e) {
            validationThrew = true;
            throw e;
        }
        return null;
    }

    /** A read-only requirement: a guard that needs durable state overrides this and declares its provider. */
    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setReadOnly(true);
        return req;
    }

    /** Every guardrail is a {@link JobType#GUARDRAIL} job, which is how its rows are told apart. */
    @Override
    public JobType getJobType() {
        return JobType.GUARDRAIL;
    }
}
