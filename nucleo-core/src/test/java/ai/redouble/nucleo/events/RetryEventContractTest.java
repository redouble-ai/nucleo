/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.events.retry.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import org.junit.jupiter.api.*;

import java.time.*;

import static ai.redouble.nucleo.events.EventFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The four retry signals a person sees: each renders its message from what it carries, is a
 * status update rather than a phase, and names itself in its title. A rate limit tells the
 * acceleration case apart from the capacity case; a missing delay reads as zero seconds.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class RetryEventContractTest {

    private final JobSnapshot s = snapshot("job-1", "wf-1");

    @Test
    void aRateLimitRetryTellsAccelerationFromCapacity() {
        RateLimitRetryEvent acceleration = new RateLimitRetryEvent(s, RateLimitType.ACCELERATION, "gpt-x", 2, 0.5, Duration.ofSeconds(12));
        assertEquals("Hit acceleration limit (system throttle: 1.5x) for gpt-x. Retrying in ~12 seconds (attempt 2)", acceleration.message());
        RateLimitRetryEvent capacity = new RateLimitRetryEvent(s, RateLimitType.CAPACITY, "gpt-x", 1, 0, Duration.ofSeconds(3));
        assertEquals("Hit capacity limit for gpt-x. Retrying in ~3 seconds (attempt 1)", capacity.message());
        assertEquals("Hit capacity limit for gpt-x. Retrying in ~0 seconds (attempt 1)",
                new RateLimitRetryEvent(s, RateLimitType.UNKNOWN, "gpt-x", 1, 0, null).message(), "no delay reads as zero seconds");
        assertEquals(RateLimitType.ACCELERATION, acceleration.getLimitType());
        assertEquals("gpt-x", acceleration.getModelName());
        assertEquals(2, acceleration.getAttemptNumber());
        assertEquals(0.5, acceleration.getCurrentThrottle());
        assertEquals(Duration.ofSeconds(12), acceleration.getEstimatedDelay());
        assertEquals(MsgType.STATUS_UPDATE, acceleration.msgType());
        assertEquals("Rate Limit Retry", acceleration.title());
        assertEquals(acceleration.message(), acceleration.getHumanMessage());
        assertEquals(JobState.RUNNING, acceleration.jobState());
    }

    @Test
    void aTransientErrorRetryNamesTheProviderAndItsWords() {
        TransientErrorRetryEvent event = new TransientErrorRetryEvent(s, "Anthropic", "overloaded_error", 3, Duration.ofSeconds(20));
        assertEquals("Server error from Anthropic: overloaded_error. Retrying in ~20 seconds (attempt 3)", event.message());
        assertEquals("Anthropic", event.getProvider());
        assertEquals("overloaded_error", event.getErrorDetails());
        assertEquals(3, event.getAttemptNumber());
        assertEquals(Duration.ofSeconds(20), event.getEstimatedDelay());
        assertEquals(MsgType.STATUS_UPDATE, event.msgType());
        assertEquals("Server Error Retry", event.title());
        assertEquals(event.message(), event.getHumanMessage());
        assertTrue(new TransientErrorRetryEvent(s, "p", "d", 1, null).message().contains("~0 seconds"), "no delay reads as zero seconds");
    }

    @Test
    void anOutputTruncationRetryNamesBothBudgets() {
        OutputTruncationRetryEvent event = new OutputTruncationRetryEvent(s, "gpt-x", 4096, 8192);
        assertEquals("Output truncated at 4096 tokens on gpt-x. Retrying with budget 8192.", event.message());
        assertEquals(4096, event.getPreviousBudget());
        assertEquals(8192, event.getNewBudget());
        assertEquals("gpt-x", event.getModelName());
        assertEquals(MsgType.STATUS_UPDATE, event.msgType());
        assertEquals("Output Truncation Retry", event.title());
        assertEquals(event.message(), event.getHumanMessage());
    }

    @Test
    void aResponseCorrectionRetryNamesTheFailureAndTheAttempt() {
        ResponseCorrectionRetryEvent event = new ResponseCorrectionRetryEvent(s, "gpt-x", 2, "JsonParseException");
        assertEquals("Response on gpt-x failed to parse or validate (JsonParseException). Retrying with correction, attempt 2.", event.message());
        assertEquals(2, event.getCorrectionAttempt());
        assertEquals("JsonParseException", event.getFailureSummary());
        assertEquals("gpt-x", event.getModelName());
        assertEquals(MsgType.STATUS_UPDATE, event.msgType());
        assertEquals("Response Correction Retry", event.title());
        assertEquals(event.message(), event.getHumanMessage());
    }
}
