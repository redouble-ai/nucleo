/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.nio.charset.*;
import java.security.*;
import java.security.spec.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The assertion an agent signs, verified with the JDK alone: three base64url parts, a
 * header naming the algorithm the key implies, claims with the agent as issuer and subject
 * and the authorization server as audience, a signature the public half verifies.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
class ClientAssertionTest {
    static final String USR = "alpha-agent";
    static final String ISSUER = "http://as.example";
    static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    static KeyPair rsa() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    static KeyPair ec() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    static JsonNode part(String compact, int index) throws Exception {
        String[] parts = compact.split("\\.");
        assertEquals(3, parts.length, "a compact JWS has three parts");
        return NucleoJsonSerializer.readTree(new String(Base64.getUrlDecoder().decode(parts[index]), StandardCharsets.UTF_8));
    }

    static boolean verifies(String compact, PublicKey key, String jdkAlgorithm) throws GeneralSecurityException {
        int dot = compact.lastIndexOf('.');
        Signature s = Signature.getInstance(jdkAlgorithm);
        s.initVerify(key);
        s.update(compact.substring(0, dot).getBytes(StandardCharsets.US_ASCII));
        return s.verify(Base64.getUrlDecoder().decode(compact.substring(dot + 1)));
    }

    @Test
    void rsaKeySignsRs256WithTheAgentAsIssuerAndSubject() throws Exception {
        KeyPair pair = rsa();
        String compact = ClientAssertion.sign(new AgentSigningKey(USR, pair.getPrivate(), "kid-1"), ISSUER, NOW);
        JsonNode header = part(compact, 0);
        assertEquals("RS256", header.get("alg").textValue());
        assertEquals("JWT", header.get("typ").textValue());
        assertEquals("kid-1", header.get("kid").textValue(), "the minter's thumbprint rides as kid");
        JsonNode claims = part(compact, 1);
        assertEquals(USR, claims.get("iss").textValue());
        assertEquals(USR, claims.get("sub").textValue());
        assertEquals(ISSUER, claims.get("aud").textValue(), "the audience is the authorization server's issuer identifier");
        assertEquals(NOW.getEpochSecond(), claims.get("iat").longValue());
        assertEquals(NOW.plus(ClientAssertion.LIFE).getEpochSecond(), claims.get("exp").longValue());
        assertDoesNotThrow(() -> UUID.fromString(claims.get("jti").textValue()), "jti is a fresh UUID");
        assertTrue(verifies(compact, pair.getPublic(), "SHA256withRSA"), "the public half verifies the signature");
    }

    @Test
    void ecKeySignsEs256InJoseForm() throws Exception {
        KeyPair pair = ec();
        String compact = ClientAssertion.sign(new AgentSigningKey(USR, pair.getPrivate(), null), ISSUER, NOW);
        assertEquals("ES256", part(compact, 0).get("alg").textValue());
        assertFalse(part(compact, 0).has("kid"), "no kid is sent when the credential has none");
        assertTrue(verifies(compact, pair.getPublic(), "SHA256withECDSAinP1363Format"), "the signature is the raw R||S form JOSE requires");
        assertNotEquals(compact, ClientAssertion.sign(new AgentSigningKey(USR, pair.getPrivate(), null), ISSUER, NOW), "every assertion carries its own jti");
    }

    @Test
    void pemRoundTripsThroughTheJdkForBothKeyTypes() throws Exception {
        for (KeyPair pair : List.of(rsa(), ec())) {
            String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n"; // gitleaks:allow: armor around a key generated in this test
            AgentSigningKey key = AgentSigningKey.fromPem(USR, pem, null);
            assertEquals(pair.getPrivate(), key.key());
        }
    }

    @Test
    void credentialsRenderWithoutTheirSecrets() throws Exception {
        assertFalse(new AgentSecret(USR, "rda_hidden").toString().contains("hidden"));
        assertFalse(new AgentSigningKey(USR, rsa().getPrivate(), null).toString().contains("MII"));
        assertFalse(new McpAccessToken("tok-hidden", null).toString().contains("hidden"));
    }
}
