/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events.retry;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import com.fasterxml.jackson.annotation.*;

/**
 * Event published when an LLM response failed to parse or validate and the job is
 * being re-run with a correction message appended to its conversation. Deterministic
 * retry - no jitter or backoff, the model simply gets a chance to fix its output. The message
 * is {@code Response on <model> failed to parse or validate (<failure>). Retrying with
 * correction, attempt <n>.}; the title is {@code Response Correction Retry}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-09)
 */
public final class ResponseCorrectionRetryEvent extends AbstractJobEvent implements RetryEvent, HumanReadable {
    private final String modelName;
    private final int correctionAttempt;
    private final String failureSummary;

    public ResponseCorrectionRetryEvent(JobSnapshot snapshot, String modelName, int correctionAttempt, String failureSummary) {
        super(snapshot);
        this.modelName = modelName;
        this.correctionAttempt = correctionAttempt;
        this.failureSummary = failureSummary;
        setMessage(buildMessage());
    }

    private String buildMessage() {
        return String.format(
            "Response on %s failed to parse or validate (%s). Retrying with correction, attempt %d.",
            modelName, failureSummary, correctionAttempt
        );
    }

    @Override
    @JsonProperty("content")
    public String getHumanMessage() {
        return message();
    }

    public String title() {
        return "Response Correction Retry";
    }

    public MsgType msgType() {
        return MsgType.STATUS_UPDATE;
    }

    public JobState jobState() {
        return snapshot().getState();
    }

    public String getModelName() {
        return modelName;
    }

    public int getCorrectionAttempt() {
        return correctionAttempt;
    }

    public String getFailureSummary() {
        return failureSummary;
    }
}
