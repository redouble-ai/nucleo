/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dispatcher's resolution step, end to end through real job submission: a declared
 * grade resolves through the configured picker and prices before acquisition; an
 * envelope-refused grade fails the job with the refusal; a transparent retry re-mints a
 * fresh binding and hands the picker the attempt history; and getRequirements() is
 * captured exactly once at submit plus once per attempt - never more.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
class ModelResolutionDispatchTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    /** Delegates to the suite's pins while recording every situation it is consulted with. */
    public static class RecordingPicker implements ModelPicker {
        static final List<Situation> SITUATIONS = new CopyOnWriteArrayList<>();
        private final ModelPicker delegate = new TestModelPicker();

        @Override
        public ModelSpec provide(Seat seat, Situation situation) {
            SITUATIONS.add(situation);
            return delegate.provide(seat, situation);
        }
    }

    private static class FlatJob extends AbstractJob<String> {
        final List<ModelBinding> mintedBindings = new ArrayList<>();
        final Grade grade;
        int requirementsCalls;
        int failuresToThrow;

        FlatJob(Identifiable parent, Grade grade) {
            super(parent, "resolution-test");
            this.grade = grade;
        }

        @Override
        public JobRequirements getRequirements() {
            requirementsCalls++;
            JobRequirements req = new JobRequirements();
            mintedBindings.add(req.requireModel(grade, Depth.STANDARD, 100, OutputDeclaration.of(50)));
            req.setRequiresTransaction(false);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws Exception {
            if (failuresToThrow > 0) {
                failuresToThrow--;
                throw new TransientErrorRetryException("simulated upstream fault", "test-provider", "boom", 503, 1, null);
            }
            return mintedBindings.get(mintedBindings.size() - 1).getModel().getId();
        }
    }

    private static class PinnedJob extends AbstractJob<String> {
        final ModelSpec pinned;
        ModelBinding binding;

        PinnedJob(Identifiable parent, ModelSpec pinned) {
            super(parent, "pinned-test");
            this.pinned = pinned;
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            binding = req.requireModel(pinned, Depth.IMMEDIATE, 10, OutputDeclaration.of(10));
            req.setRequiresTransaction(false);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return binding.getModel().getId();
        }
    }

    @Test
    void pinnedJobResolvesWithoutConsultingThePicker() throws Exception {
        Class<? extends ModelPicker> configured = Settings.get(ModelSettings.class).pickerClass;
        Settings.get(ModelSettings.class).pickerClass = RecordingPicker.class;
        ModelPickers.resetForTests();
        RecordingPicker.SITUATIONS.clear();
        try {
            PinnedJob job = new PinnedJob(Job.workflow("test-user", "resolution-pinned"), TestModels.small());
            assertEquals(TestModels.small().getId(), JobDispatcher.getInstance().submit(job).get());
            assertTrue(RecordingPicker.SITUATIONS.isEmpty(), "a pin never consults the picker");
        }
        finally {
            Settings.get(ModelSettings.class).pickerClass = configured;
            ModelPickers.resetForTests();
        }
    }

    @Test
    void pinnedJobStillDiesAtTheSealedEnvelope() {
        PinnedJob job = new PinnedJob(Job.workflow("test-user", "resolution-pinned-refused"),
                TestModels.requiringLax());
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(job).get());
        assertTrue(String.valueOf(failure.getCause().getMessage()).contains("refuses"),
                String.valueOf(failure.getCause().getMessage()));
    }

    @Test
    void declaredGradeResolvesPricesAndCapturesRequirementsOncePerAttempt() throws Exception {
        FlatJob job = new FlatJob(Job.workflow("test-user", "resolution-flat"), Grade.SMALL);
        String servedId = JobDispatcher.getInstance().submit(job).get();
        assertEquals(new TestModelPicker().provide(new Seat(FlatJob.class, Grade.SMALL, ModelKind.LLM), null).getId(), servedId);
        ModelBinding resolved = job.mintedBindings.get(job.mintedBindings.size() - 1);
        assertEquals(100 + ConversationContext.outputReserve(resolved.getModel(), 50, Depth.STANDARD), resolved.getReservation(),
                "flat pricing = in + declared out + the depth's thinking on the resolved entry, clamped as the wire is");
        assertEquals(2, job.requirementsCalls, "exactly one capture at submit plus one per attempt");
    }

    @Test
    void envelopeRefusedGradeFailsTheJobWithTheRefusal() {
        FlatJob job = new FlatJob(Job.workflow("test-user", "resolution-refused"), Grade.MEGA);
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> JobDispatcher.getInstance().submit(job).get());
        Throwable cause = failure.getCause();
        assertNotNull(cause);
        assertTrue(String.valueOf(cause.getMessage()).contains("refuses"),
                "the refusal names the envelope: " + cause.getMessage());
    }

    @Test
    void transparentRetryRemintsAFreshBindingAndCarriesAttemptHistory() throws Exception {
        Class<? extends ModelPicker> configured = Settings.get(ModelSettings.class).pickerClass;
        Settings.get(ModelSettings.class).pickerClass = RecordingPicker.class;
        ModelPickers.resetForTests();
        RecordingPicker.SITUATIONS.clear();
        try {
            FlatJob job = new FlatJob(Job.workflow("test-user", "resolution-retry"), Grade.SMALL);
            job.failuresToThrow = 1;
            String servedId = JobDispatcher.getInstance().submit(job).get();
            assertNotNull(servedId);
            assertEquals(3, job.requirementsCalls, "submit capture plus one per attempt");
            assertEquals(2, job.mintedBindings.stream().filter(ModelBinding::isResolved).count(),
                    "each attempt resolved its own fresh binding");
            assertNotSame(job.mintedBindings.get(1), job.mintedBindings.get(2), "the write-once cell is never reused");
            Situation retryConsult = RecordingPicker.SITUATIONS.get(RecordingPicker.SITUATIONS.size() - 1);
            assertNotNull(retryConsult.getAttempts(), "the re-attempt consult carries the history");
            assertEquals(1, retryConsult.getAttempts().size());
            Situation.Attempt attempt = retryConsult.getAttempts().get(0);
            assertEquals(job.mintedBindings.get(1).getModel().getId(), attempt.spec().getId());
            assertTrue(attempt.failure() instanceof TransientErrorRetryException);
        }
        finally {
            Settings.get(ModelSettings.class).pickerClass = configured;
            ModelPickers.resetForTests();
        }
    }
}
