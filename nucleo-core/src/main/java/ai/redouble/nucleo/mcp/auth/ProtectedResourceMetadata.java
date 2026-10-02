/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import java.net.*;
import java.util.*;

/**
 * The part of an RFC 9728 protected resource metadata document a client-credentials client
 * reads: which resource the document is about, and which authorization servers issue
 * tokens for it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public record ProtectedResourceMetadata(URI resource, List<URI> authorizationServers) {
    public ProtectedResourceMetadata {
        if (resource == null) {
            throw new IllegalArgumentException("Protected resource metadata names its resource");
        }
        if (authorizationServers == null || authorizationServers.isEmpty()) {
            throw new IllegalArgumentException("Protected resource metadata names at least one authorization server");
        }
        authorizationServers = List.copyOf(authorizationServers);
    }
}
