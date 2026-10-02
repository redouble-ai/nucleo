/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.errors.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Framework-triggered enforcement of the job-based guardrail kinds. Admission, content
 * and auth guards run at the dispatch path - the one place every execution route
 * crosses, which is what makes protection a property of the tool rather than of the
 * route: the dispatcher calls {@link #enforceInput} after dependency resolution and
 * before resource allocation, and {@link #enforceOutput} after resources are released
 * and before the result is delivered. Validation guards run at a producing
 * orchestrator's final-answer seat through {@link #enforceValidation}, while the
 * producer still holds its conversation and can act on a refusal. (The SCOPE rung is
 * not enforced here: scope is a pure value judgment run inline at the submission
 * door - see the dispatcher's submission rules and {@link ScopeAuthority}.)
 * <p>
 * Guards run as their own jobs, submitted through the dispatcher's internal door on
 * the gated job's behalf and awaited on the gated job's thread while it holds no
 * resources - the same safety argument as dependency waiting. The internal door runs
 * the scope wall against the guard the gated job itself was admitted under: a scoped
 * guard whose claim drifts from that flow is refused before it runs, and a guard that
 * declares {@link ScopeAuthority} is sealed with it, so any judge it spawns inherits
 * the gated flow's binding.
 * <p>
 * Three outcomes, kept apart on purpose. A {@link GuardrailException} anywhere in an
 * executed guard's failure chain is the refusal and fails the gated job with it. Any
 * other failure of an executed guard is an infrastructure error and fails the gated
 * job closed with that error as-is, never laundered into a refusal. A guard refused at
 * the door never ran and rendered no verdict on the target: that refusal is a code
 * error in the guard's own scope declaration and surfaces as a {@link SystemException}
 * of the gated job, so a producer never treats it as feedback it could act on.
 * Guardrail jobs are not {@link GuardedExecution}, so enforcement never recurses.
 * <p>
 * Zero-guard executions are an auditable fact: the enforcer writes the applied-guard
 * counts to {@link #OBS_GUARDS_INPUT} / {@link #OBS_GUARDS_OUTPUT} /
 * {@link #OBS_GUARDS_VALIDATION} on every guarded job's context.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public final class GuardrailEnforcer {
    /** Metadata key: number of input-side guards applied to this job (0 is auditable). */
    public static final String OBS_GUARDS_INPUT = "obs.guards_input";
    /** Metadata key: number of output-side guards applied to this job (0 is auditable). */
    public static final String OBS_GUARDS_OUTPUT = "obs.guards_output";
    /** Metadata key: number of validation guards applied to this job's latest candidate answer (0 is auditable). */
    public static final String OBS_GUARDS_VALIDATION = "obs.guards_validation";
    /** Metadata key: the calling job's class name, stashed at the submission door. */
    public static final String OBS_CALLER_CLASS = "obs.caller_class";

    private GuardrailEnforcer() {
    }

    /**
     * Runs the input-side guards of a guarded job: declared admission guards, declared
     * INPUT-direction content guards, then declared auth guards. First refusal or
     * failure fails the gated job before any resource is allocated.
     */
    public static void enforceInput(Job<?> job, JobContext<?> context) throws Exception {
        if (!(job instanceof GuardedExecution guarded)) {
            return;
        }
        JobSnapshot snapshot = context.getSnapshot();
        Object input = guarded.guardedInputTarget();
        int applied = 0;
        for (AdmissionGuardrail guard : guarded.declareAdmissionGuardrails()) {
            Object callerClass = context.getMetadata(OBS_CALLER_CLASS);
            guard.init(new AdmissionContext(snapshot.getUserId(), callerClass == null ? null : callerClass.toString(), job.getClass()));
            guard.setGatedSnapshot(snapshot);
            run(guard, context);
            applied++;
        }
        for (ContentGuardrail<?> guard : guarded.declareContentGuardrails()) {
            if (guard.direction() == ContentGuardrail.Direction.INPUT && applies(guard, input)) {
                prime(guard, input, snapshot);
                run(guard, context);
                applied++;
            }
        }
        for (AuthGuardrail<?> guard : guarded.declareAuthGuardrails()) {
            if (applies(guard, input)) {
                prime(guard, input, snapshot);
                run(guard, context);
                applied++;
            }
        }
        context.putMetadata(OBS_GUARDS_INPUT, applied);
    }

    /**
     * Runs the OUTPUT-direction content guards of a guarded job against its result,
     * after resources are released and before the result is delivered. A refusal turns
     * the completed attempt into a failure; the caller never sees the result.
     */
    public static void enforceOutput(Job<?> job, Object result, JobContext<?> context) throws Exception {
        if (!(job instanceof GuardedExecution guarded)) {
            return;
        }
        JobSnapshot snapshot = context.getSnapshot();
        int applied = 0;
        for (ContentGuardrail<?> guard : guarded.declareContentGuardrails()) {
            if (guard.direction() == ContentGuardrail.Direction.OUTPUT && applies(guard, result)) {
                prime(guard, result, snapshot);
                run(guard, context);
                applied++;
            }
        }
        context.putMetadata(OBS_GUARDS_OUTPUT, applied);
    }

    /**
     * Runs the given content guards of one direction against a target outside dispatch
     * enforcement, on behalf of the job whose context is given. The entry point for
     * code that must police an object that never passes through a gated dispatch -
     * e.g. a synthesized recovery result at the recovery wrapper's edge.
     */
    public static void enforceContent(List<ContentGuardrail<?>> guards, ContentGuardrail.Direction direction, Object target, JobContext<?> onBehalfOf) throws Exception {
        JobSnapshot snapshot = onBehalfOf.getSnapshot();
        for (ContentGuardrail<?> guard : guards) {
            if (guard.direction() == direction && applies(guard, target)) {
                prime(guard, target, snapshot);
                run(guard, onBehalfOf);
            }
        }
    }

    /**
     * Runs a producer's validation guards against one candidate final answer, on the
     * producer's behalf and from the producer's thread, while the producer still holds
     * its conversation. A refusal is the producer's to act on; the caller of this method
     * feeds it back into the loop.
     */
    public static void enforceValidation(List<? extends ValidationGuardrail<?>> guards, Object candidate, JobContext<?> producer) throws Exception {
        JobSnapshot snapshot = producer.getSnapshot();
        int applied = 0;
        for (ValidationGuardrail<?> guard : guards) {
            if (applies(guard, candidate)) {
                prime(guard, candidate, snapshot);
                run(guard, producer);
                applied++;
            }
        }
        producer.putMetadata(OBS_GUARDS_VALIDATION, applied);
    }

    private static boolean applies(Guardrail<?> guard, Object target) {
        return target != null && guard.targetType().isInstance(target);
    }

    @SuppressWarnings("unchecked")
    private static void prime(Guardrail<?> guard, Object target, JobSnapshot snapshot) {
        ((Guardrail<Object>) guard).setTarget(target);
        guard.setGatedSnapshot(snapshot);
    }

    /**
     * Submits one guard job through the dispatcher's internal door on the gated job's
     * behalf and blocks for its verdict. A door refusal - the guard's own scope claim
     * drifting from the gated flow - is a code error of the guard, not a verdict, and
     * fails the gated job as a {@link SystemException} carrying the refusal; a door
     * failure that already is a system error propagates as itself. Of an executed guard,
     * a GuardrailException anywhere in the failure chain rethrows as the refusal;
     * anything else rethrows as itself - fail closed with the cause preserved.
     */
    private static void run(Guardrail<?> guard, JobContext<?> gated) throws Exception {
        JobHandle<Void> handle;
        try {
            handle = JobDispatcher.getInstance().submitInternal(guard, gated);
        }
        catch (GuardrailException refusedAtDoor) {
            throw new SystemException("GuardrailEnforcer", guard.getClass().getSimpleName() + " was refused admission on behalf of "
                    + gated.getJob().getClass().getSimpleName() + " (job " + gated.getJobId() + "): " + refusedAtDoor.getLLMMessage(), refusedAtDoor);
        }
        try {
            handle.get();
        }
        catch (ExecutionException e) {
            for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
                if (t instanceof GuardrailException refusal) {
                    throw refusal;
                }
            }
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }
}
