/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import ai.redouble.nucleo.harness.schema.*;

import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.*;

/**
 * The RFC 7523 client assertion an agent signs to authenticate at a token endpoint: a
 * compact JWS whose claims name the agent as issuer and subject, the authorization
 * server's issuer identifier as audience (RFC 7523bis; the MCP conformance referee accepts
 * nothing else), a fresh {@code jti}, and a short life. Signed with the JDK alone: the
 * header and claims are the framework serializer's JSON, base64url without padding, and
 * the signature is {@code java.security.Signature} under the algorithm the key implies.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public final class ClientAssertion {
    /** RFC 7523 section 3: the assertion's life is short; a minute outlives any clock skew a server tolerates. */
    public static final Duration LIFE = Duration.ofSeconds(60);
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private ClientAssertion() {
    }

    /**
     * @param key      the agent's signing key
     * @param audience the authorization server's issuer identifier
     * @param now      the assertion's issue time
     * @return the compact serialization, three base64url parts
     */
    public static String sign(AgentSigningKey key, String audience, Instant now) throws GeneralSecurityException {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", key.jwsAlgorithm());
        header.put("typ", "JWT");
        if (key.keyId() != null) {
            header.put("kid", key.keyId());
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", key.usr());
        claims.put("sub", key.usr());
        claims.put("aud", audience);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(LIFE).getEpochSecond());
        String signingInput = encode(NucleoJsonSerializer.write(header)) + "." + encode(NucleoJsonSerializer.write(claims));
        Signature signature = Signature.getInstance(key.jdkSignatureAlgorithm());
        signature.initSign(key.key());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + BASE64URL.encodeToString(signature.sign());
    }

    private static String encode(String json) {
        return BASE64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
