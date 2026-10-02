/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;
/**
 * EPO OPS service buckets as reported in the {@code X-Throttling-Control}
 * response header. Each bucket is rate-limited independently by EPO, and
 * {@code EPORateLimiter} holds one sliding window per bucket so pressure on
 * one service (e.g. {@code SEARCH} hitting its minute quota) does not
 * throttle the others.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public enum EPOService {
    /** Published-data search endpoint (CQL queries). */
    SEARCH,
    /** Published-data retrieval: biblio, claims, description, abstract. */
    RETRIEVAL,
    /** INPADOC family and legal-status endpoints. */
    INPADOC,
    /** Published-data images endpoint. */
    IMAGES,
    /** Everything not covered by the four buckets above (number-service, classification, etc.). */
    OTHER
}
