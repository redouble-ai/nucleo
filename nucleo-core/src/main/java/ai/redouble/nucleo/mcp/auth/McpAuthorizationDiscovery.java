/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import java.net.*;
import java.util.*;
import java.util.regex.*;

/**
 * The URL arithmetic of the MCP authorization specification (2025-11-25), as pure
 * functions: where a protected resource's metadata lives, where an authorization server's
 * metadata lives, and what the {@code WWW-Authenticate} challenge says about the former.
 * <ul>
 *   <li>RFC 9728 section 3: the protected resource metadata URL is the resource identifier
 *       with {@code /.well-known/oauth-protected-resource} inserted between host and path;
 *       the specification has a client try that path-inserted form first and the root
 *       form second.</li>
 *   <li>RFC 8414 section 3 and OpenID Discovery: an issuer with a path is tried at the
 *       path-inserted OAuth form, the path-inserted OpenID form, and the path-appended
 *       OpenID form, in that order; an issuer without a path at the OAuth form then the
 *       OpenID form.</li>
 *   <li>RFC 9728 section 5.1: the challenge's {@code resource_metadata} parameter, when
 *       present, names the metadata URL and takes precedence over the well-known forms.</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public final class McpAuthorizationDiscovery {
    static final String PROTECTED_RESOURCE_WELL_KNOWN = "/.well-known/oauth-protected-resource";
    static final String AUTHORIZATION_SERVER_WELL_KNOWN = "/.well-known/oauth-authorization-server";
    static final String OPENID_WELL_KNOWN = "/.well-known/openid-configuration";
    static final String RESOURCE_METADATA_PARAMETER = "resource_metadata";
    private static final Pattern RESOURCE_METADATA = Pattern.compile("(?i)\\bresource_metadata\\s*=\\s*(?:\"([^\"]*)\"|([^\\s,]+))");

    private McpAuthorizationDiscovery() {
    }

    /**
     * The {@code resource_metadata} URL a bearer challenge names, or null when the header
     * carries none (the well-known forms are then the client's next step). Both the
     * quoted-string and the bare-token forms are read; the header value is otherwise not
     * interpreted and never repeated.
     */
    public static URI resourceMetadataUrl(String wwwAuthenticate) {
        if (wwwAuthenticate == null) {
            return null;
        }
        Matcher m = RESOURCE_METADATA.matcher(wwwAuthenticate);
        if (!m.find()) {
            return null;
        }
        String value = m.group(1) != null ? m.group(1) : m.group(2);
        try {
            return new URI(value);
        }
        catch (URISyntaxException e) {
            // Not chained: URISyntaxException's message carries the input, and the header is never repeated.
            throw new IllegalArgumentException("The bearer challenge's " + RESOURCE_METADATA_PARAMETER + " is not a URL");
        }
    }

    /**
     * The canonical resource identifier of an MCP server (RFC 8707 section 2, the MCP
     * specification's canonical form): scheme and host lowercased, the default port
     * dropped, no query, no fragment, no trailing slash on a path.
     */
    public static URI canonicalResource(URI mcp) {
        String scheme = mcp.getScheme().toLowerCase(Locale.ROOT);
        String host = mcp.getHost().toLowerCase(Locale.ROOT);
        int port = mcp.getPort();
        boolean defaultPort = port == -1 || (port == 80 && scheme.equals("http")) || (port == 443 && scheme.equals("https"));
        String path = mcp.getRawPath() == null ? "" : mcp.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return URI.create(scheme + "://" + host + (defaultPort ? "" : ":" + port) + path);
    }

    /**
     * Where to look for the protected resource metadata of {@code mcp} without a
     * challenge: the path-inserted form when the resource has a path, then the root form.
     */
    public static List<URI> wellKnownProtectedResourceUrls(URI mcp) {
        URI canonical = canonicalResource(mcp);
        String origin = canonical.getScheme() + "://" + canonical.getRawAuthority();
        String path = canonical.getRawPath();
        List<URI> urls = new ArrayList<>();
        if (!path.isEmpty()) {
            urls.add(URI.create(origin + PROTECTED_RESOURCE_WELL_KNOWN + path));
        }
        urls.add(URI.create(origin + PROTECTED_RESOURCE_WELL_KNOWN));
        return urls;
    }

    /**
     * Where to look for an authorization server's metadata, in the order the MCP
     * specification prescribes for an issuer with and without a path component.
     */
    public static List<URI> wellKnownAuthorizationServerUrls(URI issuer) {
        String origin = issuer.getScheme() + "://" + issuer.getRawAuthority();
        String path = issuer.getRawPath() == null ? "" : issuer.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        List<URI> urls = new ArrayList<>();
        if (path.isEmpty()) {
            urls.add(URI.create(origin + AUTHORIZATION_SERVER_WELL_KNOWN));
            urls.add(URI.create(origin + OPENID_WELL_KNOWN));
        }
        else {
            urls.add(URI.create(origin + AUTHORIZATION_SERVER_WELL_KNOWN + path));
            urls.add(URI.create(origin + OPENID_WELL_KNOWN + path));
            urls.add(URI.create(origin + path + OPENID_WELL_KNOWN));
        }
        return urls;
    }
}
