/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;


/**
 * Type of rate limit encountered.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-23)
 */
public enum RateLimitType {
    /**
     * Normal capacity limits (TPM/RPM).
     */
    CAPACITY,

    /**
     * Velocity-based acceleration limits (usage growth too fast).
     */
    ACCELERATION,

    /**
     * Unable to determine type.
     */
    UNKNOWN
}
