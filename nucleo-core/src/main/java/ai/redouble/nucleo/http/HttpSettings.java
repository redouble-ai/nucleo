/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.http;

import ai.redouble.nucleo.*;

/**
 * The HTTP package's knobs: the size of the one shared connection pool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
public class HttpSettings extends Settings {

    /**
     * The one shared HTTP pool: max connections, as the total, as the per-route maximum,
     * and as the per-job permit gate alike. Read once when {@link HttpConnectionPools}
     * builds. The pool is socket hygiene, not a throttle guard: provider quota lives with
     * the pre-emptive rate limiters, and the OS-side ceilings (file descriptors, ephemeral
     * ports) sit orders of magnitude above this.
     */
    public volatile int poolSize = 1000;
}
