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
 * Event published when an LLM response hit {@code stop_reason=max_tokens} and the
 * job is being re-run with an enlarged output budget. Deterministic retry - no
 * jitter or backoff, just a new acquisition at the larger size. The message is
 * {@code Output truncated at <previous> tokens on <model>. Retrying with budget <new>.};
 * the title is {@code Output Truncation Retry}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public final class OutputTruncationRetryEvent extends AbstractJobEvent implements RetryEvent, HumanReadable {
    private final String modelName;
    private final int previousBudget;
    private final int newBudget;

    public OutputTruncationRetryEvent(JobSnapshot snapshot, String modelName, int previousBudget, int newBudget) {
        super(snapshot);
        this.modelName = modelName;
        this.previousBudget = previousBudget;
        this.newBudget = newBudget;
        setMessage(buildMessage());
    }

    private String buildMessage() {
        return String.format(
            "Output truncated at %d tokens on %s. Retrying with budget %d.",
            previousBudget, modelName, newBudget
        );
    }

    @Override
    @JsonProperty("content")
    public String getHumanMessage() {
        return message();
    }

    public String title() {
        return "Output Truncation Retry";
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

    public int getPreviousBudget() {
        return previousBudget;
    }

    public int getNewBudget() {
        return newBudget;
    }
}
