/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

/**
 * What every job the demo submits has in common: a person is waiting behind it in a
 * browser, so the runtime's defaults for unattended work are tightened here, in one place
 * each job's constructor reads.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public final class DemoPolicy {
    /**
     * One transparent re-run on an upstream retry signal: a blip clears on the second
     * attempt, and an endpoint still failing then should show as a failed row or section
     * within a minute rather than hold a race or a page for the runtime's default budget.
     */
    public static final int UPSTREAM_RETRIES = 1;

    private DemoPolicy() {}
}
