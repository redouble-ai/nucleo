/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.schema.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The mechanical contract of the guardrail core, independent of dispatch enforcement
 * (which {@link GuardrailEnforcementTest} covers): the hierarchy is sealed to its four
 * kinds and each branch base labels its rung; a guardrail is a read-only leaf job of type
 * GUARDRAIL with a ten-second timeout whose final {@code execute} calls {@code validate}
 * on the target it was given; it records its target, the gated job and the rung before
 * it runs and PASS or FAIL after; it never authors a scope guard but accepts the seal;
 * an admission guard judges its context on a Void target; an auth guard reads the
 * principal off the snapshot or answers null; a tool declares nothing by default; and a
 * refusal is a correctable exception that speaks to the model as a guardrail violation.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class GuardrailContractTest {
    private static final Identifiable ROOT = Job.workflow("test-user", "guardrail-contract-test");

    static class RecordingContentGuard extends AbstractContentGuardrail<Object> {
        final List<Object> validated = new ArrayList<>();
        private final Direction direction;

        RecordingContentGuard(Direction direction) {
            super(ROOT);
            this.direction = direction;
        }

        @Override
        public Direction direction() {
            return direction;
        }

        @Override
        public Class<Object> targetType() {
            return Object.class;
        }

        @Override
        public void validate(Object target) {
            validated.add(target);
        }
    }

    static class RecordingAdmissionGuard extends AbstractAdmissionGuardrail {
        final AtomicInteger checks = new AtomicInteger();

        RecordingAdmissionGuard() {
            super(ROOT);
        }

        @Override
        protected void checkAdmission() {
            checks.incrementAndGet();
        }

        AdmissionContext context() {
            return getAdmissionContext();
        }
    }

    static class PrincipalReadingAuthGuard extends AbstractAuthGuardrail<Object> {
        PrincipalReadingAuthGuard() {
            super(ROOT);
        }

        @Override
        public Class<Object> targetType() {
            return Object.class;
        }

        @Override
        public void validate(Object target) {
        }

        String principal() {
            return getPrincipal();
        }
    }

    static class PassingValidationGuard extends AbstractValidationGuardrail<String> {
        PassingValidationGuard() {
            super(ROOT);
        }

        @Override
        public Class<String> targetType() {
            return String.class;
        }

        @Override
        public void validate(String target) {
        }
    }

    /** A job class to stand in as the gated tool on a snapshot. */
    static class GatedTool extends AbstractJob<String> {
        GatedTool() {
            super(ROOT, "gated-tool");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "";
        }
    }

    /** An author setting a timeout of their own, the way a concrete guard does in its constructor. */
    static class SlowGuard extends AbstractContentGuardrail<Object> {
        SlowGuard() {
            super(ROOT);
            setTimeout(Duration.ofMinutes(2));
        }

        @Override
        public Direction direction() {
            return Direction.INPUT;
        }

        @Override
        public Class<Object> targetType() {
            return Object.class;
        }

        @Override
        public void validate(Object target) {
        }
    }

    static JobSnapshot snapshotOf(String userId) {
        return new JobSnapshot("job-7", null, "wf-1", userId, GatedTool.class, JobType.TOOL, "GatedTool", null, null,
                JobState.QUEUED, 0, Instant.now(), null, null, List.of(), Map.of());
    }

    static JobContext<Void> contextFor(AbstractGuardrail<?> guard) {
        return new JobContext<>(guard, "test-user", Duration.ofSeconds(10), guard.getRequirements());
    }

    @Test
    void theHierarchyIsSealedToTheFourKinds() {
        Set<Class<?>> permitted = new HashSet<>(Arrays.asList(Guardrail.class.getPermittedSubclasses()));
        assertEquals(Set.of(AuthGuardrail.class, ContentGuardrail.class, AdmissionGuardrail.class, ValidationGuardrail.class), permitted,
                "one kind per rung of the context ladder, and no other");
        for (Class<?> kind : permitted) {
            assertFalse(kind.isSealed(), kind.getSimpleName() + " is the open branch a concrete guardrail extends");
        }
    }

    @Test
    void eachBranchBaseImplementsItsKindAndLabelsItsRung() {
        assertEquals("CONTENT_INPUT", new RecordingContentGuard(ContentGuardrail.Direction.INPUT).gateRungLabel());
        assertEquals("CONTENT_OUTPUT", new RecordingContentGuard(ContentGuardrail.Direction.OUTPUT).gateRungLabel());
        assertEquals("AUTH", new PrincipalReadingAuthGuard().gateRungLabel());
        assertEquals("ADMISSION", new RecordingAdmissionGuard().gateRungLabel());
        assertEquals("VALIDATION", new PassingValidationGuard().gateRungLabel());
        assertInstanceOf(ContentGuardrail.class, new RecordingContentGuard(ContentGuardrail.Direction.INPUT));
        assertInstanceOf(AuthGuardrail.class, new PrincipalReadingAuthGuard());
        assertInstanceOf(AdmissionGuardrail.class, new RecordingAdmissionGuard());
        assertInstanceOf(ValidationGuardrail.class, new PassingValidationGuard());
    }

    @Test
    void executeIsFinalAndValidatesTheTargetItWasGiven() throws Exception {
        Method execute = AbstractGuardrail.class.getMethod("execute", JobResources.class, JobContext.class);
        assertTrue(Modifier.isFinal(execute.getModifiers()), "a concrete guardrail cannot bypass validate");
        RecordingContentGuard guard = new RecordingContentGuard(ContentGuardrail.Direction.INPUT);
        guard.setTarget("the payload");
        assertNull(guard.execute(null, contextFor(guard)), "a guardrail's job result is nothing; its verdict is the exception or its absence");
        assertEquals(List.of("the payload"), guard.validated, "execute validates exactly the target set before submission");
    }

    @Test
    void aGuardrailIsAReadOnlyLeafJobWithATenSecondTimeout() {
        RecordingContentGuard guard = new RecordingContentGuard(ContentGuardrail.Direction.INPUT);
        assertTrue(guard.getRequirements().isReadOnly(), "a guardrail declares a read-only requirement");
        assertEquals(JobType.GUARDRAIL, guard.getJobType());
        assertEquals(Duration.ofSeconds(10), guard.getTimeout(), "the ten-second default holds unless the author sets another");
        assertEquals(Duration.ofMinutes(2), new SlowGuard().getTimeout(), "the author may set another");
    }

    @Test
    void beforeRunningAGuardRecordsItsTargetTheGatedJobAndTheRung() {
        RecordingContentGuard guard = new RecordingContentGuard(ContentGuardrail.Direction.OUTPUT);
        Map<String, Object> target = Map.of("field", "value");
        guard.setTarget(target);
        guard.setGatedSnapshot(snapshotOf("alice"));
        JobContext<Void> context = contextFor(guard);
        guard.preExecute(context);
        assertEquals(NucleoJsonSerializer.write(target), context.getMetadata(AbstractGuardrail.OBS_TARGET), "the target in its serialized form");
        assertEquals("job-7", context.getMetadata(AbstractGuardrail.OBS_GATES_JOB_ID), "the gated job's id");
        assertEquals("CONTENT_OUTPUT", context.getMetadata(AbstractGuardrail.OBS_GATES_PHASE), "the rung");
    }

    @Test
    void afterRunningAGuardRecordsPassOrFailByWhetherValidateThrew() throws Exception {
        RecordingContentGuard passing = new RecordingContentGuard(ContentGuardrail.Direction.INPUT);
        JobContext<Void> passed = contextFor(passing);
        passing.execute(null, passed);
        passing.postExecute(passed);
        assertEquals("PASS", passed.getMetadata("obs.output"));
        PayloadSizeCapGuardrail refusing = new PayloadSizeCapGuardrail(ROOT, 1);
        refusing.setTarget("longer than one character");
        JobContext<Void> refused = contextFor(refusing);
        assertThrows(GuardrailException.class, () -> refusing.execute(null, refused));
        refusing.postExecute(refused);
        assertEquals("FAIL", refused.getMetadata("obs.output"), "the guard records its own verdict, whatever the context knows");
    }

    @Test
    void aGuardNeverAuthorsAScopeGuardButAcceptsTheDoorsSeal() {
        RecordingContentGuard guard = new RecordingContentGuard(ContentGuardrail.Direction.INPUT);
        assertNull(guard.getScopeGuard(), "unsealed until the internal door seals it");
        assertThrows(UnsupportedOperationException.class, () -> guard.setScopeGuard(new ScopeGuard(new TenantScope("t1"))),
                "a guard's binding is the gated flow's, never its own");
        ScopeGuard effective = new ScopeGuard(new TenantScope("t1"));
        guard.sealScopeGuard(effective);
        assertSame(effective, guard.getScopeGuard(), "the seal is what the door composed");
    }

    @Test
    void theGatedSnapshotIsNullOutsideEnforcement() {
        PrincipalReadingAuthGuard guard = new PrincipalReadingAuthGuard();
        assertNull(guard.getGatedSnapshot(), "a guard run outside dispatch enforcement gates no job");
        assertNull(guard.principal(), "and so has no principal to read");
        guard.setGatedSnapshot(snapshotOf("alice"));
        assertEquals("alice", guard.principal(), "the principal is the gated job's user");
    }

    @Test
    void anAdmissionGuardJudgesItsContextOnAVoidTarget() throws Exception {
        RecordingAdmissionGuard guard = new RecordingAdmissionGuard();
        assertEquals(Void.class, guard.targetType(), "no target: admission runs before a call exists");
        AdmissionContext context = new AdmissionContext("alice", "com.example.Agent", GatedTool.class);
        guard.init(context);
        assertSame(context, guard.context(), "init delivers the immutable identity payload");
        guard.validate(null);
        assertEquals(1, guard.checks.get(), "validate is checkAdmission");
        JobContext<Void> jobContext = contextFor(guard);
        guard.preExecute(jobContext);
        assertEquals("alice via com.example.Agent -> GatedTool", jobContext.getMetadata(AbstractGuardrail.OBS_TARGET),
                "an admission guard records who reached what through whom instead of a target");
        assertEquals("ADMISSION", jobContext.getMetadata(AbstractGuardrail.OBS_GATES_PHASE));
    }

    @Test
    void aToolDeclaresNoGuardrailsByDefault() {
        GuardedExecution tool = () -> "input";
        assertEquals(List.of(), tool.declareContentGuardrails());
        assertEquals(List.of(), tool.declareAuthGuardrails());
        assertEquals(List.of(), tool.declareAdmissionGuardrails());
        assertEquals("input", tool.guardedInputTarget());
    }

    @Test
    void aRefusalIsCorrectableAndSpeaksToTheModelAsAGuardrailViolation() {
        GuardrailException refusal = new GuardrailException("the amount exceeds the per-transfer limit");
        assertTrue(refusal.isCorrectable(), "the agent loop may act on a refusal");
        assertEquals("the amount exceeds the per-transfer limit", refusal.getMessage());
        assertEquals("Guardrail violation: the amount exceeds the per-transfer limit", refusal.getLLMMessage(),
                "the model is told it met a guardrail, then the reason");
        assertTrue(refusal.explainToLLM().contains("may be correctable"), "the correctable branch's hint follows");
        IllegalStateException cause = new IllegalStateException("lookup failed");
        assertSame(cause, new GuardrailException("m", cause).getCause(), "a cause is kept");
    }
}
