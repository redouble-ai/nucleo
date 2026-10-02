/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import ai.redouble.nucleo.mcp.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An endpoint's identity to the client pool includes who it connects as: one URL under two
 * credentials or two header sets is two clients. The two configurations the endpoint
 * refuses at transport creation are pinned too.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
class HTTPMCPEndpointIdTest {
    static final String URL = "http://h:8090/mcp";

    static HTTPMCPEndpoint endpoint(McpClientCredential credential, Map<String, String> headers, HTTPMCPEndpoint.Flavor flavor) {
        HTTPMCPEndpoint e = new HTTPMCPEndpoint();
        e.setUrl(URL);
        e.setCredential(credential);
        e.setHeaders(headers);
        e.setFlavor(flavor);
        return e;
    }

    @Test
    void identityFoldsInTheAgentAndTheHeaders() {
        String bare = endpoint(null, null, HTTPMCPEndpoint.Flavor.STREAMABLE_HTTP).getEndpointId();
        String asAlpha = endpoint(new AgentSecret("alpha-agent", "s"), null, HTTPMCPEndpoint.Flavor.STREAMABLE_HTTP).getEndpointId();
        String asBeta = endpoint(new AgentSecret("beta-agent", "s"), null, HTTPMCPEndpoint.Flavor.STREAMABLE_HTTP).getEndpointId();
        String keyed = endpoint(null, Map.of("Authorization", "Bearer k1"), HTTPMCPEndpoint.Flavor.STREAMABLE_HTTP).getEndpointId();
        String otherKey = endpoint(null, Map.of("Authorization", "Bearer k2"), HTTPMCPEndpoint.Flavor.STREAMABLE_HTTP).getEndpointId();
        assertEquals(URL, bare);
        assertEquals(4, new HashSet<>(List.of(bare, asAlpha, asBeta, keyed)).size(), "every identity is its own client");
        assertNotEquals(keyed, otherKey, "two API keys to one URL are two clients");
        assertFalse(keyed.contains("k1"), "the id is logged, so the header value is digested rather than inlined");
        assertTrue(asAlpha.contains("alpha-agent"));
    }

    @Test
    void credentialOnSseIsRefused() {
        HTTPMCPEndpoint e = endpoint(new AgentSecret("alpha-agent", "s"), null, HTTPMCPEndpoint.Flavor.SSE);
        assertThrows(IllegalStateException.class, () -> e.createTransport(new ObjectMapper()),
                "the SSE transport has no seam to drop a rejected token");
    }

    @Test
    void credentialAndAuthorizationHeaderTogetherAreRefused() {
        HTTPMCPEndpoint e = endpoint(new AgentSecret("alpha-agent", "s"), Map.of("authorization", "Bearer k"), HTTPMCPEndpoint.Flavor.STREAMABLE_HTTP);
        assertThrows(IllegalStateException.class, () -> e.createTransport(new ObjectMapper()), "one identity per endpoint");
    }
}
