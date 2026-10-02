/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

/**
 * A host's store of credentials a person pastes into the page, held for the process only. The
 * two hosts hold them the way their own config machinery reads them - Spring as a property
 * source ahead of the rest, Quarkus as a MicroProfile config source - so the runtime's secrets
 * store sees them through the same path a vault or an environment variable uses. The shared
 * catalog logic ({@link CatalogAdmin}) needs only to hand a credential over and to ask whether
 * one is already held, so those are the two operations named here; everything else about how
 * they are stored is the host's.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public interface SessionStore {
    /**
     * Holds one credential for the rest of this process: the id is the runtime's record name
     * ({@code anthropic-api-key}, {@code aws-region}); user, secret and host are its parts, a
     * blank part left unset. Effective from the next lookup.
     */
    void provide(String id, String user, String secret, String host);

    /** Whether this store holds any part of the credential - a session credential may be corrected, a deployment's never. */
    boolean holds(String id);
}
