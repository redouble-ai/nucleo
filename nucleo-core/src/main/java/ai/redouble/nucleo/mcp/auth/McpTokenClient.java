/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.http.*;
import ai.redouble.nucleo.mcp.server.*;
import com.fasterxml.jackson.databind.*;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.entity.*;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.*;
import org.apache.hc.core5.http.message.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/**
 * Obtains a bearer token for an MCP server the way the MCP authorization specification
 * (2025-11-25) and its client-credentials extension prescribe, against any conformant
 * server: discover the protected resource metadata (the well-known forms first, the
 * server's own bearer challenge when it serves neither), read the authorization server's
 * metadata, check that it offers the client-credentials grant and this credential's
 * authentication method, and post the token request carrying the RFC 8707 {@code resource}
 * indicator. A third-party client of a third-party server, so it is built on the lib's
 * base for those: every failure is the framework's typed exception, every upstream body is
 * quoted as received, redirects are never followed (a token or a secret must not travel to
 * a host the metadata did not name), and every document is read by the boundary's strict
 * reader ({@link McpBoundaryJson}), which refuses duplicate keys, comments, and bytes after
 * the document instead of guessing.
 * <p>
 * What is verified about the documents, per the RFCs: the protected resource metadata
 * describes the resource it was fetched for (RFC 9728 section 3.3); the authorization
 * server metadata names the issuer it was fetched for (RFC 8414 section 3.3) and a token
 * endpoint on that issuer's host; absent {@code grant_types_supported} means the RFC 8414
 * default, which has no client-credentials grant; absent
 * {@code token_endpoint_auth_methods_supported} means the RFC 8414 default,
 * {@code client_secret_basic} only.
 * <p>
 * Nothing the credential holds enters a message: the secret rides in a header, the
 * assertion in a form body, and neither is part of the endpoint or the body a failure
 * quotes.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public class McpTokenClient extends AbstractApiClient {
    private static final String SERVICE = "MCP authorization";
    private static final String JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    private static final String BEARER_TOKEN_TYPE = "Bearer";
    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";
    private static final ObjectMapper STRICT = McpBoundaryJson.strictObjectMapper();
    private static final Pattern BEARER_TOKEN_SHAPE = Pattern.compile("^[A-Za-z0-9\\-._~+/]+=*$");

    public McpTokenClient() {
        setHttpClient(HttpConnectionPools.getInstance().getClient());
    }

    @Override
    protected String getServiceName() {
        return SERVICE;
    }

    /** Every path this client requests is an absolute URL the metadata named. */
    @Override
    protected String getBaseUrl() {
        return "";
    }

    /**
     * Redirects are never followed: a 3xx then arrives as a response, which the base class
     * maps to the typed failure naming the endpoint, whatever body rides with it, because
     * the alternative is a credential travelling to wherever the redirect points.
     */
    @Override
    protected void decorateRequest(HttpUriRequestBase request) {
        request.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
    }

    /**
     * RFC 8414 section 3.2 and RFC 6749 section 5.1: the documents and the token response
     * are {@code application/json}. A success served as anything else is not a document
     * this client can say it read.
     */
    @Override
    protected LLMReadableCheckedException classify(String endpoint, int statusCode, Header[] headers, String responseBody) {
        if (statusCode >= 300) {
            return null;
        }
        for (Header header : headers) {
            if (header.getName().equalsIgnoreCase("Content-Type")) {
                try {
                    if (ContentType.parse(header.getValue()).isSameMimeType(ContentType.APPLICATION_JSON)) {
                        return null;
                    }
                }
                catch (IllegalArgumentException malformed) {
                    // The header's own text is the caller's; the rule is all that is said.
                }
                return new ExternalServiceException(SERVICE, endpoint + " answered in a media type other than application/json");
            }
        }
        return new ExternalServiceException(SERVICE, endpoint + " answered without a media type; application/json is required");
    }

    /**
     * The whole flow: discovery, metadata, token.
     *
     * @param mcp        the MCP server's URL as configured
     * @param credential the agent's proof
     * @return a token the MCP server accepts as bearer
     */
    public McpAccessToken acquire(URI mcp, McpClientCredential credential) throws LLMReadableCheckedException {
        URI resource = McpAuthorizationDiscovery.canonicalResource(mcp);
        ProtectedResourceMetadata protectedResource = discover(mcp, resource);
        AuthorizationServerMetadata server = serverMetadata(protectedResource.authorizationServers().get(0));
        if (!server.grantTypesSupported().contains(AuthorizationServerMetadata.CLIENT_CREDENTIALS)) {
            throw new ExternalServiceException(SERVICE, "the authorization server " + server.issuer()
                    + " does not offer the " + AuthorizationServerMetadata.CLIENT_CREDENTIALS + " grant");
        }
        if (!server.tokenEndpointAuthMethodsSupported().contains(credential.tokenEndpointAuthMethod())) {
            throw new ExternalServiceException(SERVICE, "the authorization server " + server.issuer()
                    + " does not accept " + credential.tokenEndpointAuthMethod() + " at its token endpoint");
        }
        return mint(server, resource, credential);
    }

    /**
     * The protected resource metadata of {@code mcp}: the well-known forms in the
     * specification's order, then the URL the server's own challenge names.
     */
    ProtectedResourceMetadata discover(URI mcp, URI resource) throws LLMReadableCheckedException {
        for (URI url : McpAuthorizationDiscovery.wellKnownProtectedResourceUrls(mcp)) {
            try {
                return protectedResource(url, resource);
            }
            catch (Http404Exception absent) {
                // The specification has the client try the next form.
            }
        }
        URI named;
        try {
            named = McpAuthorizationDiscovery.resourceMetadataUrl(probeChallenge(mcp));
        }
        catch (IllegalArgumentException notAUrl) {
            // Not chained as the cause: the parser's complaint would repeat the header value.
            throw new ExternalServiceException(SERVICE, "the bearer challenge of " + resource + " names a resource_metadata that is not a URL");
        }
        if (named == null) {
            throw new UnauthorizedException(SERVICE, "the MCP server at " + resource
                    + " serves no protected resource metadata at either well-known form and its bearer challenge names none");
        }
        // The challenge's URL is judged like every URL a document names: http or https, absolute.
        return protectedResource(httpsOrHttp(named.toString(), resource), resource);
    }

    /**
     * A document from a third party is read by the boundary's strict reader, not the
     * in-process one: a duplicate key, a comment, or bytes after the document are a document
     * we cannot say we read, and the in-process reader would guess.
     */
    private JsonNode document(URI url) throws LLMReadableCheckedException {
        HttpGet request = new HttpGet(url);
        request.setHeader("Accept", "application/json");
        return executeRequest(request, url.toString(), STRICT::readTree);
    }

    ProtectedResourceMetadata protectedResource(URI url, URI resource) throws LLMReadableCheckedException {
        JsonNode document = document(url);
        String described = document.path("resource").textValue();
        if (described == null) {
            throw new ExternalServiceException(SERVICE, "the protected resource metadata at " + url + " names no resource");
        }
        if (!McpAuthorizationDiscovery.canonicalResource(httpsOrHttp(described, url)).equals(resource)) {
            throw new ExternalServiceException(SERVICE, "the protected resource metadata at " + url
                    + " describes a different resource than " + resource);
        }
        JsonNode servers = document.path("authorization_servers");
        if (!servers.isArray() || servers.isEmpty()) {
            throw new ExternalServiceException(SERVICE, "the protected resource metadata at " + url + " names no authorization server");
        }
        List<URI> issuers = new ArrayList<>();
        for (JsonNode server : servers) {
            if (!server.isTextual()) {
                throw new ExternalServiceException(SERVICE, "the protected resource metadata at " + url + " lists an authorization server that is not a URL");
            }
            issuers.add(httpsOrHttp(server.textValue(), url));
        }
        return new ProtectedResourceMetadata(httpsOrHttp(described, url), issuers);
    }

    /**
     * The bearer challenge of an unauthenticated request: a JSON-RPC ping the server refuses
     * before it reads it. Any answer other than a 401 with a challenge means the server does
     * not do what the credential configured for it assumes.
     */
    String probeChallenge(URI mcp) throws LLMReadableCheckedException {
        HttpPost probe = new HttpPost(mcp);
        probe.setHeader("Accept", "application/json, text/event-stream");
        Map<String, Object> ping = new LinkedHashMap<>();
        ping.put("jsonrpc", "2.0");
        ping.put("id", 0);
        ping.put("method", "ping");
        probe.setEntity(new StringEntity(NucleoJsonSerializer.write(ping), ContentType.APPLICATION_JSON));
        decorateRequest(probe);
        try {
            HttpReply reply = httpClient.execute(probe, HttpReply.reader());
            if (reply.status() != 401) {
                throw new ExternalServiceException(SERVICE, "the MCP server at " + mcp + " answered an unauthenticated request with HTTP "
                        + reply.status() + " instead of a bearer challenge; the credential configured for it assumes one");
            }
            String challenge = reply.header(WWW_AUTHENTICATE);
            if (challenge == null) {
                throw new UnauthorizedException(SERVICE, "the MCP server at " + mcp + " refused an unauthenticated request without a " + WWW_AUTHENTICATE + " challenge");
            }
            return challenge;
        }
        catch (IOException e) {
            throw new ExternalServiceException(SERVICE, "probing " + mcp + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * The authorization server's metadata, from the first well-known form that answers.
     */
    AuthorizationServerMetadata serverMetadata(URI issuer) throws LLMReadableCheckedException {
        List<URI> urls = McpAuthorizationDiscovery.wellKnownAuthorizationServerUrls(issuer);
        for (URI url : urls) {
            try {
                return serverMetadata(document(url), issuer, url);
            }
            catch (Http404Exception absent) {
                // The specification has the client try the next form.
            }
        }
        throw new ExternalServiceException(SERVICE, "the authorization server " + issuer + " serves no metadata at any of " + urls);
    }

    AuthorizationServerMetadata serverMetadata(JsonNode document, URI issuer, URI url) throws LLMReadableCheckedException {
        String declared = document.path("issuer").textValue();
        if (declared == null || !sameIssuer(httpsOrHttp(declared, url), issuer)) {
            throw new ExternalServiceException(SERVICE, "the metadata at " + url + " does not name " + issuer + " as its issuer");
        }
        String tokenEndpoint = document.path("token_endpoint").textValue();
        if (tokenEndpoint == null) {
            throw new ExternalServiceException(SERVICE, "the metadata at " + url + " names no token endpoint");
        }
        URI token = httpsOrHttp(tokenEndpoint, url);
        if (!sameHost(token, issuer)) {
            throw new ExternalServiceException(SERVICE, "the metadata at " + url + " names a token endpoint on a host other than the issuer's, or under another scheme");
        }
        Set<String> grants = strings(document, "grant_types_supported", Set.of("authorization_code", "implicit"), url);
        Set<String> methods = strings(document, "token_endpoint_auth_methods_supported", Set.of(McpClientCredential.CLIENT_SECRET_BASIC), url);
        return new AuthorizationServerMetadata(httpsOrHttp(declared, url), token, grants, methods);
    }

    /**
     * The token request: {@code client_credentials} with the {@code resource} indicator, the
     * credential as an assertion in the body or as HTTP Basic.
     */
    McpAccessToken mint(AuthorizationServerMetadata server, URI resource, McpClientCredential credential) throws LLMReadableCheckedException {
        HttpPost request = new HttpPost(server.tokenEndpoint());
        request.setHeader("Accept", "application/json");
        List<NameValuePair> form = new ArrayList<>();
        form.add(new BasicNameValuePair("grant_type", AuthorizationServerMetadata.CLIENT_CREDENTIALS));
        form.add(new BasicNameValuePair("resource", resource.toString()));
        switch (credential) {
            case AgentSigningKey key -> {
                form.add(new BasicNameValuePair("client_assertion_type", JWT_BEARER));
                form.add(new BasicNameValuePair("client_assertion", assertion(key, server.issuer())));
            }
            case AgentSecret secret -> request.setHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (encode(secret.usr()) + ":" + encode(secret.secret())).getBytes(StandardCharsets.UTF_8)));
        }
        request.setEntity(new UrlEncodedFormEntity(form, StandardCharsets.UTF_8));
        JsonNode response = executeRequest(request, server.tokenEndpoint().toString(), STRICT::readTree);
        String token = response.path("access_token").textValue();
        if (token == null || token.isBlank()) {
            throw new ExternalServiceException(SERVICE, "the token endpoint " + server.tokenEndpoint() + " answered without an access_token");
        }
        // RFC 6750 section 2.1: a bearer is a b64token. Anything else could not travel in a
        // header, or would smuggle more than a token into one.
        if (!BEARER_TOKEN_SHAPE.matcher(token).matches()) {
            throw new ExternalServiceException(SERVICE, "the token endpoint " + server.tokenEndpoint() + " minted an access_token that is not a bearer token (RFC 6750 b64token)");
        }
        String type = response.path("token_type").textValue();
        if (!BEARER_TOKEN_TYPE.equalsIgnoreCase(type)) {
            throw new ExternalServiceException(SERVICE, "the token endpoint " + server.tokenEndpoint() + " minted a token of a type other than " + BEARER_TOKEN_TYPE);
        }
        JsonNode expiresIn = response.path("expires_in");
        Instant expiresAt = null;
        if (!expiresIn.isMissingNode()) {
            // A value past a long would keep its low bits; a value past what an Instant holds
            // would overflow. Either is an expiry we cannot represent, not one to guess at.
            if (!expiresIn.isIntegralNumber() || !expiresIn.canConvertToLong() || expiresIn.longValue() < 0) {
                throw new ExternalServiceException(SERVICE, "the token endpoint " + server.tokenEndpoint() + " sent an expires_in that is not a non-negative integer within range");
            }
            try {
                expiresAt = Instant.now().plusSeconds(expiresIn.longValue());
            }
            catch (DateTimeException | ArithmeticException beyondTime) {
                throw new ExternalServiceException(SERVICE, "the token endpoint " + server.tokenEndpoint() + " sent an expires_in beyond any representable instant", beyondTime);
            }
        }
        return new McpAccessToken(token, expiresAt);
    }

    private static String assertion(AgentSigningKey key, URI issuer) throws LLMReadableCheckedException {
        try {
            return ClientAssertion.sign(key, issuer.toString(), Instant.now());
        }
        catch (GeneralSecurityException e) {
            throw new SystemException(SERVICE, "signing the client assertion for " + key.usr() + " failed", e);
        }
    }

    private static Set<String> strings(JsonNode document, String field, Set<String> rfc8414Default, URI url) throws LLMReadableCheckedException {
        JsonNode node = document.path(field);
        if (node.isMissingNode()) {
            return rfc8414Default;
        }
        if (!node.isArray()) {
            throw new ExternalServiceException(SERVICE, "the metadata at " + url + " has a " + field + " that is not a list");
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                throw new ExternalServiceException(SERVICE, "the metadata at " + url + " has a " + field + " entry that is not a string");
            }
            values.add(value.textValue());
        }
        return values;
    }

    /** A URL the metadata names must be http or https; anything else is refused as not a place a token may go. */
    private static URI httpsOrHttp(String value, URI documentUrl) throws LLMReadableCheckedException {
        URI uri;
        try {
            uri = new URI(value);
        }
        catch (URISyntaxException e) {
            throw new ExternalServiceException(SERVICE, "the metadata at " + documentUrl + " names a URL that does not parse", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null) {
            throw new ExternalServiceException(SERVICE, "the metadata at " + documentUrl + " names a URL that is not http or https");
        }
        return uri;
    }

    /** RFC 3986 equivalence for issuers: the same identifier with and without a trailing slash. */
    private static boolean sameIssuer(URI declared, URI fetchedFor) {
        return McpAuthorizationDiscovery.canonicalResource(declared).equals(McpAuthorizationDiscovery.canonicalResource(fetchedFor));
    }

    private static boolean sameHost(URI a, URI b) {
        URI ca = McpAuthorizationDiscovery.canonicalResource(a);
        URI cb = McpAuthorizationDiscovery.canonicalResource(b);
        return ca.getScheme().equals(cb.getScheme()) && ca.getRawAuthority().equals(cb.getRawAuthority());
    }
}
