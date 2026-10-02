/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.http;

import ai.redouble.nucleo.harness.admission.*;

/**
 * The admission account for the shared HTTP connection pool: one permit per admitted job that
 * makes HTTP calls, sized to the pool. It is the ceiling on admitted HTTP-using jobs; HTTP
 * users that are not jobs still share the pool and are bounded by its connection request
 * timeout.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public final class HttpConnectionGate extends CountingGate {

    HttpConnectionGate(int capacity) {
        super(capacity);
    }

    @Override
    public String limiterName() {
        return "http";
    }
}
