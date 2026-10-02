/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events.retry;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import com.fasterxml.jackson.annotation.*;

import java.time.*;

/**
 * Event published when a job hits a rate limit and will be transparently retried.
 * Informs users about rate limiting without alarming them. The message is
 * {@code Hit acceleration limit (system throttle: <1+throttle>x) for <model>. Retrying in ~<s>
 * seconds (attempt <n>)} for an acceleration limit and {@code Hit capacity limit for <model>.
 * Retrying in ~<s> seconds (attempt <n>)} for any other kind; the delay reads as zero seconds
 * when none is known. The title is {@code Rate Limit Retry}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-23)
 */
public final class RateLimitRetryEvent extends AbstractJobEvent implements RetryEvent, HumanReadable {
    private final RateLimitType limitType;
    private final String modelName;
    private final int attemptNumber;
    private final double currentThrottle;
    private final Duration estimatedDelay;

    public RateLimitRetryEvent(JobSnapshot snapshot, RateLimitType limitType, String modelName, int attemptNumber, double currentThrottle, Duration estimatedDelay) {
        super(snapshot);
        this.limitType = limitType;
        this.modelName = modelName;
        this.attemptNumber = attemptNumber;
        this.currentThrottle = currentThrottle;
        this.estimatedDelay = estimatedDelay;
        setMessage(buildMessage());
    }

    private String buildMessage() {
        String limitDesc;
        if (limitType == RateLimitType.ACCELERATION) {
            limitDesc = String.format("acceleration limit (system throttle: %.1fx)", 1.0 + currentThrottle);
        } else {
            limitDesc = "capacity limit";
        }
        long seconds = estimatedDelay != null ? estimatedDelay.getSeconds() : 0;
        return String.format(
            "Hit %s for %s. Retrying in ~%d seconds (attempt %d)",
            limitDesc, modelName, seconds, attemptNumber
        );
    }

    @Override
    @JsonProperty("content")
    public String getHumanMessage() {
        return message();
    }

    public String title() {
        return "Rate Limit Retry";
    }

    public MsgType msgType() {
        return MsgType.STATUS_UPDATE;
    }

    public JobState jobState() {
        return snapshot().getState();
    }

    public RateLimitType getLimitType() {
        return limitType;
    }

    public String getModelName() {
        return modelName;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public double getCurrentThrottle() {
        return currentThrottle;
    }

    public Duration getEstimatedDelay() {
        return estimatedDelay;
    }
}
