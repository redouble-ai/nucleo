/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import java.net.*;
import java.util.*;

/**
 * The part of an RFC 8414 authorization server metadata document a client-credentials
 * client reads: the issuer identifier (the assertion's audience), the token endpoint, and
 * whether the server offers the grant and the authentication method the client needs.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public record AuthorizationServerMetadata(URI issuer, URI tokenEndpoint, Set<String> grantTypesSupported, Set<String> tokenEndpointAuthMethodsSupported) {
    static final String CLIENT_CREDENTIALS = "client_credentials";

    public AuthorizationServerMetadata {
        if (issuer == null) {
            throw new IllegalArgumentException("Authorization server metadata names its issuer");
        }
        if (tokenEndpoint == null) {
            throw new IllegalArgumentException("Authorization server metadata names its token endpoint");
        }
        if (grantTypesSupported == null) {
            throw new IllegalArgumentException("Authorization server metadata lists its grant types");
        }
        if (tokenEndpointAuthMethodsSupported == null) {
            throw new IllegalArgumentException("Authorization server metadata lists its token endpoint authentication methods");
        }
        grantTypesSupported = Set.copyOf(grantTypesSupported);
        tokenEndpointAuthMethodsSupported = Set.copyOf(tokenEndpointAuthMethodsSupported);
    }
}
