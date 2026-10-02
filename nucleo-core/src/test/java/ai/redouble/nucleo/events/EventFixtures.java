/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;

import java.time.*;
import java.util.*;

/**
 * The one snapshot shape the event tests share: a running tool job of the plain {@link Job}
 * class, so job-type and workflow scoping can be exercised against it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
final class EventFixtures {

    private EventFixtures() {
    }

    static JobSnapshot snapshot(String jobId, String workflowId) {
        return snapshot(jobId, workflowId, Instant.now());
    }

    static JobSnapshot snapshot(String jobId, String workflowId, Instant startedAt) {
        return new JobSnapshot(jobId, null, workflowId, "test-user", Job.class, JobType.TOOL,
                "fixture job", null, null, JobState.RUNNING, 1, Instant.now(), startedAt, null,
                List.of(), Map.of());
    }
}
