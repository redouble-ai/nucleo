/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.events.retry.*;
import ai.redouble.nucleo.harness.*;

/**
 * Sealed category for discrete notifications surfaced to end users:
 * warnings, successes, informational messages, and errors that are not
 * themselves job-lifecycle transitions but warrant user attention.
 *
 * <p>Distinct from {@link ProgressEvent}, which conveys ongoing progress,
 * and from {@link RetryEvent}, which is a platform recovery decision.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public sealed interface NotificationEvent extends JobEvent
        permits UserNotificationEvent {
}
