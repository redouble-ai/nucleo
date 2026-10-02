/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.secrets;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The environment store's one rule, and the find/require contract every store honours.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-10)
 */
class EnvironmentSecretsTest {

    private static EnvironmentSecrets over(Map<String, String> variables) {
        return new EnvironmentSecrets(variables::get);
    }

    @Test
    void idsBecomeUpperSnakeCaseVariableNames() {
        assertEquals("ANTHROPIC_API_KEY", EnvironmentSecrets.variableName("anthropic-api-key"));
        assertEquals("NCBI_API_KEY", EnvironmentSecrets.variableName("ncbi.api.key"));
        assertEquals("MYODP_API_KEY", EnvironmentSecrets.variableName("myodp-api-key"));
    }

    /** An id nobody declared (a tool's own key, here EPO's consumer key and secret) follows the one rule. */
    @Test
    void theThreePartsOfAnUndeclaredIdComeFromTheBaseNameAndItsTwoSuffixes() {
        Secrets secrets = over(Map.of(
                "EPO_API_KEY", "s3cr3t",
                "EPO_API_KEY_USER", "consumer-key",
                "NCBI_API_KEY_HOST", "https://eutils.example.test"));
        Credential epo = secrets.require("epo-api-key");
        assertEquals("consumer-key", epo.user());
        assertEquals("s3cr3t", epo.secret());
        assertNull(epo.host());
        Credential ncbi = secrets.require("ncbi-api-key");
        assertNull(ncbi.secret(), "a part the environment does not hold is null, never an empty string");
        assertEquals("https://eutils.example.test", ncbi.host());
    }

    @Test
    void findIsNullWhenNothingIsSetAndEmptyCountsAsUnset() {
        Secrets secrets = over(Map.of("EPO_API_KEY", ""));
        assertNull(secrets.find("epo-api-key"));
        assertNull(secrets.find("never-configured"));
    }

    @Test
    void requireNamesTheIdAndTheVariableToSet() {
        SecretUnavailableException refusal = assertThrows(SecretUnavailableException.class,
                () -> over(Map.of()).require("anthropic-api-key"));
        assertEquals("anthropic-api-key", refusal.getId());
        assertTrue(refusal.getMessage().contains("anthropic-api-key"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("ANTHROPIC_API_KEY"),
                "the failure tells the reader exactly what to export: " + refusal.getMessage());
    }

    @Test
    void theStoreDescribesHowToProvideAnIdInItsOwnTerms() {
        assertEquals("the environment variable NCBI_API_KEY (and NCBI_API_KEY_USER / NCBI_API_KEY_HOST when the credential has a user or host part)",
                over(Map.of()).describe("ncbi-api-key"));
    }

    @Test
    void aCredentialNeverPrintsItsSecret() {
        String printed = new Credential("me", "hunter2", null).toString();
        assertFalse(printed.contains("hunter2"), printed);
        assertTrue(printed.contains("me"), printed);
    }
}
