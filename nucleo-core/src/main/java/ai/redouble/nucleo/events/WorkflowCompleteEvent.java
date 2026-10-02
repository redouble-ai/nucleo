/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import com.fasterxml.jackson.annotation.*;

/**
 * Terminal event signaling that an entire workflow has completed (successfully or not).
 *
 * <p>Published by the job dispatcher when the root job of a workflow finishes.
 * This is the definitive signal for the UI to stop spinners, show results, or
 * display errors. Only published for root jobs - sub-job completions/failures
 * are internal to the workflow.</p>
 *
 * <p>Implements both {@link WorkflowTerminationEvent} (triggers observer self-cleanup)
 * and {@link HumanReadable} (a workflow-scoped observer forwards it to the person watching).</p>
 *
 * <p>Distinct from {@link MessageCompleteEvent} which signals completion of a single
 * message exchange within a long-lived chat session.</p>
 *
 * <p>Carries the outcome and the reason, which is also its message; the one-argument form is
 * a success reading {@code Workflow completed successfully}. A success answers
 * {@code COMPLETING} under the title {@code Workflow Complete}, a failure {@code FAILING}
 * under {@code Workflow Failed}.</p>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-11)
 * @see MessageCompleteEvent
 * @see WorkflowTerminationEvent
 */
public final class WorkflowCompleteEvent extends AbstractJobEvent implements WorkflowTerminationEvent, HumanReadable {
    private final boolean successful;
    private final String reason;

    /**
     * Creates a workflow complete event.
     *
     * @param snapshot the root job's snapshot
     * @param successful whether the workflow completed successfully
     * @param reason description of the outcome
     */
    public WorkflowCompleteEvent(JobSnapshot snapshot, boolean successful, String reason) {
        super(snapshot);
        this.successful = successful;
        this.reason = reason;
        setMessage(reason);
    }

    /**
     * Creates a successful workflow complete event.
     *
     * @param snapshot the root job's snapshot
     */
    public WorkflowCompleteEvent(JobSnapshot snapshot) {
        this(snapshot, true, "Workflow completed successfully");
    }

    @Override
    public String getTerminationReason() {
        return reason;
    }

    @Override
    @JsonProperty("successful")
    public boolean isSuccessful() {
        return successful;
    }

    public MsgType msgType() {
        return successful ? MsgType.COMPLETING : MsgType.FAILING;
    }

    public String title() {
        return successful ? "Workflow Complete" : "Workflow Failed";
    }

    public JobState jobState() {
        return snapshot().getState();
    }

    @Override
    @JsonProperty("content")
    public String getHumanMessage() {
        return message();
    }

    @JsonProperty("reason")
    public String getReason() {
        return reason;
    }
}
