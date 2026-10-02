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

import java.time.*;

/**
 * Event published when a job hits a transient server error and will be retried. The
 * dispatcher's retry loop publishes it for both a plain 5xx and a 529 overload, and the error
 * details name which. Distinguished from {@link RateLimitRetryEvent} because provider failover
 * triggers on sustained server errors, not rate limits. The message is
 * {@code Server error from <provider>: <details>. Retrying in ~<s> seconds (attempt <n>)},
 * the delay reading as zero seconds when none is known; the title is {@code Server Error Retry}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-07)
 */
public final class TransientErrorRetryEvent extends AbstractJobEvent implements RetryEvent, HumanReadable {
    private final String provider;
    private final String errorDetails;
    private final int attemptNumber;
    private final Duration estimatedDelay;

    public TransientErrorRetryEvent(JobSnapshot snapshot, String provider, String errorDetails, int attemptNumber, Duration estimatedDelay) {
        super(snapshot);
        this.provider = provider;
        this.errorDetails = errorDetails;
        this.attemptNumber = attemptNumber;
        this.estimatedDelay = estimatedDelay;
        setMessage(buildMessage());
    }

    private String buildMessage() {
        long seconds = estimatedDelay != null ? estimatedDelay.getSeconds() : 0;
        return String.format(
            "Server error from %s: %s. Retrying in ~%d seconds (attempt %d)",
            provider, errorDetails, seconds, attemptNumber
        );
    }

    @Override
    @JsonProperty("content")
    public String getHumanMessage() {
        return message();
    }

    public String title() {
        return "Server Error Retry";
    }

    public MsgType msgType() {
        return MsgType.STATUS_UPDATE;
    }

    public JobState jobState() {
        return snapshot().getState();
    }

    public String getProvider() {
        return provider;
    }

    public String getErrorDetails() {
        return errorDetails;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public Duration getEstimatedDelay() {
        return estimatedDelay;
    }
}
