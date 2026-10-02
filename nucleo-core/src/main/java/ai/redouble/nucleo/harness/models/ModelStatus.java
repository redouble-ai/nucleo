/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

/**
 * Whether a catalog entry may be served, and who said so. Only {@link #OPEN} entries are
 * picked or pinged; the others stay in the catalog so historical runs remain priceable
 * and so the discovery knows the deployment's mind about the model. Kept is kept: a closed
 * entry is in the file for the runs it served, and nothing about it says it is available.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public enum ModelStatus {
    /** Servable. The default; an entry that says nothing is OPEN. */
    OPEN,
    /**
     * The deployment's own decision, written by hand: not served, never pinged by the
     * discovery, never changed by it. A newer model of a disabled one arrives disabled, and so
     * does the same model on another channel of the same platform.
     */
    DISABLED,
    /**
     * The vendor's word: retired or legacy upstream, written by the discovery from the
     * provider's lifecycle flag. Not served, not pinged; kept so the runs it served still price.
     */
    DEPRECATED,
    /**
     * The account's word: its listing no longer names the model (a deleted Azure deployment,
     * access withdrawn on Bedrock), written by the discovery when a run finds an open entry
     * gone from the listing. Not served, not pinged; kept so the runs it served still price.
     * A status the discovery also clears: a later run whose listing names the model again
     * reopens the entry and pings it.
     */
    UNLISTED,
    /**
     * This deployment's reach: the provider's endpoint is not served where the process
     * points (a Bedrock surface absent from the configured region), or the model's own ping
     * failed for a reason no retry changes. Written by the discovery on an entry kept from
     * the deployment's file, since a seed entry in that state is simply left out. Not served,
     * not pinged; kept so the runs it served still price. Cleared by the discovery like
     * {@link #UNLISTED}: a later run whose provider answers reopens the entry and pings it.
     */
    UNREACHABLE
}
