/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events.retry;

import ai.redouble.nucleo.harness.*;

/**
 * Sealed category for retry / recovery signals. Fired when the platform
 * decides to attempt an operation again after a transient failure, a rate
 * limit, or an internal retry policy decision.
 *
 * <p>Subscribers that surface retries to the user (chat UIs, long-running
 * job consoles) subscribe to this category directly. Every member is human-readable, renders
 * its message from what it carries, is a {@code STATUS_UPDATE} rather than a phase, and names
 * itself in its title: {@code Rate Limit Retry}, {@code Server Error Retry},
 * {@code Output Truncation Retry}, {@code Response Correction Retry}. An estimated delay that
 * is not known reads as zero seconds.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public sealed interface RetryEvent extends JobEvent
        permits RateLimitRetryEvent, TransientErrorRetryEvent, OutputTruncationRetryEvent, ResponseCorrectionRetryEvent {
}
