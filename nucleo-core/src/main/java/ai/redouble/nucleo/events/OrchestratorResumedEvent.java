/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;

/**
 * Published when an orchestrator (thinker/doer) resumes work after being idle.
 * Transitions the job to {@link JobState#RUNNING}; its message and human message are "Processing".
 *
 * <p>For {@code ReactiveThinker}: published when a new user message is received.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-08)
 */
public final class OrchestratorResumedEvent extends AbstractJobEvent implements LifecycleEvent, HumanReadable {

    public OrchestratorResumedEvent(JobSnapshot snapshot) {
        super(snapshot);
        setMessage("Processing");
    }

    @Override
    public String getHumanMessage() {
        return message();
    }
}
