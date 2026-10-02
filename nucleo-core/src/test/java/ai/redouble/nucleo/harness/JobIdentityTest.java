/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The identity a job is born with and the names a snapshot gives it: a root's workflow from
 * {@link Job#workflow}, an {@link AbstractJob}'s id, lineage, priority and name, the defaults
 * every job inherits from {@link Job}, the display name, action and description a
 * {@link JobSnapshot} resolves, the terminal and failure predicates of {@link JobState}, and
 * the once-only {@link AbstractStoppable#stop()}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class JobIdentityTest {

    static class PlainJob extends AbstractJob<String> {
        PlainJob(Identifiable parent, String prefix) {
            super(parent, prefix);
        }

        @Override
        public JobRequirements getRequirements() {
            return null;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "plain";
        }
    }

    @DisplayName(value = "Named Fixture", action = "Naming")
    @ToolDescription("what the fixture is for")
    static class AnnotatedJob extends PlainJob {
        private final String instanceName;

        AnnotatedJob(Identifiable parent, String instanceName) {
            super(parent, "annotated");
            this.instanceName = instanceName;
        }

        @Override
        public String getDisplayName() {
            return instanceName;
        }
    }

    @Test
    void aWorkflowIsItsOwnRootAndRefusesAMissingUserOrPrefix() {
        Identifiable root = Job.workflow("user-1", "ingest");
        assertEquals(root.getJobId(), root.getWorkflowId(), "a root's workflow id is its own id");
        assertNull(root.getParentJobId(), "a root has no parent");
        assertEquals("user-1", root.getUserId());
        assertTrue(root.getJobId().startsWith("ingest-"), "the prefix leads the id: " + root.getJobId());
        assertThrows(IllegalArgumentException.class, () -> Job.workflow(null, "ingest"));
        assertThrows(IllegalArgumentException.class, () -> Job.workflow("", "ingest"));
        assertThrows(IllegalArgumentException.class, () -> Job.workflow("user-1", null));
        assertThrows(IllegalArgumentException.class, () -> Job.workflow("user-1", ""));
    }

    @Test
    void aJobMintsItsIdAndInheritsItsLineage() {
        Identifiable root = Job.workflow("user-2", "wf");
        PlainJob prefixed = new PlainJob(root, "fetch");
        assertTrue(prefixed.getId().startsWith("fetch-"), "the given prefix leads the id");
        assertEquals(prefixed.getId(), prefixed.getJobId(), "getJobId is getId");
        assertEquals(root.getJobId(), prefixed.getParentJobId(), "the parent is the identity it was built from");
        assertEquals(root.getWorkflowId(), prefixed.getWorkflowId());
        assertEquals("user-2", prefixed.getUserId());
        assertEquals(0, prefixed.getPriority(), "a job's priority is zero unless it says otherwise");
        assertEquals("PlainJob-" + prefixed.getId(), prefixed.getName(), "the name is the class and the id");
        PlainJob unprefixed = new PlainJob(root, null);
        assertTrue(unprefixed.getId().startsWith("PlainJob-"), "no prefix: the class simple name leads");
        assertNotEquals(prefixed.getId(), new PlainJob(root, "fetch").getId(), "every job gets its own tail");
        assertThrows(IllegalArgumentException.class, () -> new PlainJob(null, "orphan"), "a job without a parent identity is refused");
    }

    @Test
    void theDefaultsAJobInherits() {
        PlainJob job = new PlainJob(Job.workflow("user-3", "wf"), "d");
        assertEquals(JobType.JOB, job.getJobType());
        assertNull(job.getDisplayName(), "no instance display name unless overridden");
        assertEquals(Duration.ofMinutes(30), job.getTimeout());
        job.setTimeout(null);
        assertNull(job.getTimeout(), "null declares no deadline");
    }

    @Test
    void aSnapshotNamesTheJobByOverrideThenAnnotationThenClass() {
        Identifiable root = Job.workflow("user-4", "wf");
        JobSnapshot overridden = new JobSnapshot(new AnnotatedJob(root, "Instance Name"));
        assertEquals("Instance Name", overridden.getDisplayName(), "the job's own display name wins");
        JobSnapshot annotated = new JobSnapshot(new AnnotatedJob(root, null));
        assertEquals("Named Fixture", annotated.getDisplayName(), "then the annotation");
        assertEquals("Naming", annotated.getAction(), "the action is the annotation's");
        assertEquals("what the fixture is for", annotated.getDescription(), "the description is the tool description, read reflectively");
        JobSnapshot bare = new JobSnapshot(new PlainJob(root, "bare"));
        assertEquals("PlainJob", bare.getDisplayName(), "then the class simple name");
        assertEquals("", bare.getAction(), "no annotation: an empty action");
        assertNull(bare.getDescription(), "no annotation: no description");
        assertEquals(JobState.QUEUED, bare.getState(), "a fresh snapshot is QUEUED");
        assertEquals(0, bare.getAttemptNumber());
        assertEquals("PlainJob", bare.jobType(), "jobType() is the class simple name");
        JobSnapshot later = bare.withState(JobState.RUNNING).withAttemptNumber(2).withDescription("later");
        assertEquals(JobState.RUNNING, later.getState());
        assertEquals(2, later.getAttemptNumber());
        assertEquals("later", later.getDescription(), "a runtime description overrides the annotation's");
        assertEquals(JobState.QUEUED, bare.getState(), "with* copies; the original is untouched");
    }

    @Test
    void jobStatePredicates() {
        for (JobState terminal : new JobState[] {JobState.COMPLETED, JobState.FAILED, JobState.CANCELLED, JobState.TIMED_OUT}) {
            assertTrue(terminal.isTerminal(), terminal + " is terminal");
            assertFalse(terminal.isActive());
        }
        for (JobState active : new JobState[] {JobState.QUEUED, JobState.SCHEDULED, JobState.RUNNING, JobState.CANCELLING, JobState.IDLE}) {
            assertTrue(active.isActive(), active + " is active");
            assertFalse(active.isTerminal());
        }
        assertTrue(JobState.FAILED.isFailure());
        assertTrue(JobState.TIMED_OUT.isFailure());
        assertFalse(JobState.CANCELLED.isFailure(), "a cancellation is terminal and not a failure");
        assertTrue(JobState.COMPLETED.isSuccess());
        assertFalse(JobState.COMPLETED.isFailure());
    }

    @Test
    void stopRunsDoStopOnceWithTheFlagAlreadyRaised() {
        AtomicInteger stops = new AtomicInteger();
        AtomicBoolean flagDuringStop = new AtomicBoolean();
        AbstractStoppable stoppable = new AbstractStoppable() {
            @Override
            protected void doStop() {
                stops.incrementAndGet();
                flagDuringStop.set(isStopping());
            }
        };
        assertFalse(stoppable.isStopping());
        stoppable.stop();
        stoppable.stop();
        assertEquals(1, stops.get(), "stop is idempotent: doStop runs exactly once");
        assertTrue(flagDuringStop.get(), "isStopping already answers true inside doStop");
        assertTrue(stoppable.isStopping());
    }
}
