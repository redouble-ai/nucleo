/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.*;

/**
 * A private key minted for an agent; its public half sits on the agent's credential row and
 * the authorization server verifies the assertions this key signs. RSA keys sign RS256, EC
 * P-256 keys sign ES256: the algorithm is a property of the key, never a choice.
 *
 * @param usr   the agent's usr, the OAuth client id
 * @param key   the private key
 * @param keyId the JWK thumbprint the minter printed, sent as the assertion's {@code kid}
 *              so a server holding several keys for the agent selects this one; null sends
 *              no {@code kid} and leaves the server to try every key of the algorithm
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public record AgentSigningKey(String usr, PrivateKey key, String keyId) implements McpClientCredential {
    private static final String PEM_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_END = "-----END PRIVATE KEY-----";

    public AgentSigningKey {
        if (usr == null || usr.isBlank()) {
            throw new IllegalArgumentException("A signing credential needs the agent's usr");
        }
        if (!(key instanceof RSAPrivateKey) && !(key instanceof ECPrivateKey)) {
            throw new IllegalArgumentException("A signing credential needs an RSA or EC private key");
        }
    }

    /**
     * Reads the PKCS#8 PEM the minter printed. The key type decides the algorithm: the PEM
     * is tried as RSA and then as EC, which is what PKCS#8 encodes inside the DER.
     */
    public static AgentSigningKey fromPem(String usr, String pkcs8Pem, String keyId) throws GeneralSecurityException {
        String base64 = pkcs8Pem.replace(PEM_BEGIN, "").replace(PEM_END, "").replaceAll("\\s", "");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64));
        try {
            return new AgentSigningKey(usr, KeyFactory.getInstance("RSA").generatePrivate(spec), keyId);
        }
        catch (InvalidKeySpecException notRsa) {
            return new AgentSigningKey(usr, KeyFactory.getInstance("EC").generatePrivate(spec), keyId);
        }
    }

    @Override
    public String tokenEndpointAuthMethod() {
        return PRIVATE_KEY_JWT;
    }

    /** The JWS {@code alg} this key signs. */
    public String jwsAlgorithm() {
        return key instanceof RSAPrivateKey ? "RS256" : "ES256";
    }

    /**
     * The JDK signature algorithm behind {@link #jwsAlgorithm()}. ES256 in JOSE is the raw
     * R||S concatenation, which the JDK produces directly under the P1363 format name.
     */
    public String jdkSignatureAlgorithm() {
        return key instanceof RSAPrivateKey ? "SHA256withRSA" : "SHA256withECDSAinP1363Format";
    }

    /** The key stays out of every string a debugger or a log might render. */
    @Override
    public String toString() {
        return "AgentSigningKey[" + usr + ", " + jwsAlgorithm() + (keyId == null ? "" : ", kid " + keyId) + "]";
    }
}
