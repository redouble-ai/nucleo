/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import org.junit.jupiter.api.*;

import java.net.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The URL arithmetic of discovery: the challenge's {@code resource_metadata} in both header
 * forms, the canonical resource, the well-known forms in the specification's order.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
class McpAuthorizationDiscoveryTest {

    @Test
    void challengeParameterIsReadQuotedOrBare() {
        assertEquals(URI.create("https://h/.well-known/oauth-protected-resource/mcp"),
                McpAuthorizationDiscovery.resourceMetadataUrl("Bearer error=\"invalid_token\", resource_metadata=\"https://h/.well-known/oauth-protected-resource/mcp\", scope=\"x\""));
        assertEquals(URI.create("https://h/prm"),
                McpAuthorizationDiscovery.resourceMetadataUrl("Bearer resource_metadata=https://h/prm"));
        assertNull(McpAuthorizationDiscovery.resourceMetadataUrl("Bearer realm=\"x\""), "a challenge without the parameter names nothing");
        assertNull(McpAuthorizationDiscovery.resourceMetadataUrl(null));
        assertThrows(IllegalArgumentException.class, () -> McpAuthorizationDiscovery.resourceMetadataUrl("Bearer resource_metadata=\"::not a url\""));
    }

    @Test
    void canonicalResourceDropsDefaultPortQueryAndTrailingSlash() {
        assertEquals(URI.create("https://mcp.example/mcp"), McpAuthorizationDiscovery.canonicalResource(URI.create("HTTPS://MCP.Example:443/mcp/?x=1#f")));
        assertEquals(URI.create("http://h:8090/mcp"), McpAuthorizationDiscovery.canonicalResource(URI.create("http://h:8090/mcp")));
        assertEquals(URI.create("http://h"), McpAuthorizationDiscovery.canonicalResource(URI.create("http://h/")));
    }

    @Test
    void protectedResourceFormsArePathInsertedThenRoot() {
        assertEquals(List.of(URI.create("http://h:8090/.well-known/oauth-protected-resource/mcp"), URI.create("http://h:8090/.well-known/oauth-protected-resource")),
                McpAuthorizationDiscovery.wellKnownProtectedResourceUrls(URI.create("http://h:8090/mcp/")));
        assertEquals(List.of(URI.create("https://h/.well-known/oauth-protected-resource")),
                McpAuthorizationDiscovery.wellKnownProtectedResourceUrls(URI.create("https://h/")), "a resource without a path has only the root form");
    }

    @Test
    void authorizationServerFormsFollowTheIssuerShape() {
        assertEquals(List.of(URI.create("https://as/.well-known/oauth-authorization-server"), URI.create("https://as/.well-known/openid-configuration")),
                McpAuthorizationDiscovery.wellKnownAuthorizationServerUrls(URI.create("https://as/")));
        assertEquals(List.of(URI.create("https://as/.well-known/oauth-authorization-server/tenant"),
                        URI.create("https://as/.well-known/openid-configuration/tenant"),
                        URI.create("https://as/tenant/.well-known/openid-configuration")),
                McpAuthorizationDiscovery.wellKnownAuthorizationServerUrls(URI.create("https://as/tenant")));
    }
}
