/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

/**
 * Package-private doors of the harness that tests in other packages need to open. Test scope
 * only: the production API stays as narrow as it is.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public final class TestDoors {

    private TestDoors() {
    }

    /** Cancels a job through its context, the way the dispatcher does. */
    public static void cancel(JobContext<?> context, String reason) {
        context.cancel(reason);
    }
}
