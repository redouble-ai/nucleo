/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.ratelimiters;

import ai.redouble.nucleo.harness.admission.*;

/**
 * Rate limiter for the USPTO Open Data Portal API.
 *
 * <p>ODP enforces burst=1 (one concurrent request per API key) at 4-15 req/sec.
 * {@link #getMaxRequests()} = 1 so the semaphore serializes access.
 * 250ms window gives ~4 req/sec throughput, well within ODP's floor.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class UsptoOdpRateLimiter extends ElasticWindowRateLimiter {
    @Override
    protected long getBaseWindowMs() {
        return 250;
    }
    @Override
    protected int getMaxRequests() {
        return 1;
    }
    @Override
    public String limiterName() {
        return "uspto-odp";
    }
}
