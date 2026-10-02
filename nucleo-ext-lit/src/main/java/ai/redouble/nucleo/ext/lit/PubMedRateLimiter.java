/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit;

import ai.redouble.nucleo.harness.admission.*;

/**
 * Rate limiter for PubMed/NCBI E-utilities API.
 * <p>
 * Enforces NCBI's 10 QPS limit for users with an API key.
 * This limiter is shared between all PubMed tools (search and fetch)
 * since they access the same API with the same credentials.
 * <p>
 * A tool books one slot per job and admission holds it for the job's
 * duration, like a pooled connection: PubMedSearchTool's two sequential
 * API calls (ESearch then EFetch) ride the one held slot.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-15)
 */
public class PubMedRateLimiter extends ElasticWindowRateLimiter {
    /** The id of the NCBI API key in the deployment's secret store, shared by every E-utilities and PubChem consumer. */
    public static final String NCBI_SECRET_ID = "ncbi-api-key";
    PubMedRateLimiter() {}
    @Override protected long getBaseWindowMs() { return 1_000; }
    @Override protected int getMaxRequests() { return 10; }
}