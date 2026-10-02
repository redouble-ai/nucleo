/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.secrets;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The contract of the {@link Secrets} seam itself, beyond the environment store: a store that
 * does not describe its own terms is described as "the credential '<id>' in <its class>";
 * {@link Secrets#configured()} is one store, built once from {@code SecretsSettings.secretsClass}, and
 * reads environment variables when nothing is configured; and {@link SecretUnavailableException}
 * carries the id and the cause a store could not answer with. How a deployment assigns
 * {@code SecretsSettings.secretsClass} is the configurator bootstrap's step, outside this
 * package.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class SecretsContractTest {

    /** The one-method store the package documentation shows a deployment writing. */
    static class VaultSecrets implements Secrets {
        @Override
        public Credential find(String id) {
            return null;
        }
    }

    @Test
    void aStoreWithoutItsOwnWordsIsDescribedByIdAndClass() {
        assertEquals("the credential 'epo-api-key' in VaultSecrets", new VaultSecrets().describe("epo-api-key"));
    }

    @Test
    void requireOnSuchAStoreNamesTheIdAndTheStore() {
        SecretUnavailableException refusal = assertThrows(SecretUnavailableException.class, () -> new VaultSecrets().require("epo-api-key"));
        assertEquals("epo-api-key", refusal.getId());
        assertTrue(refusal.getMessage().contains("the credential 'epo-api-key' in VaultSecrets"),
                "the failure says where to provide it: " + refusal.getMessage());
    }

    @Test
    void theConfiguredStoreIsBuiltOnceAndReadsTheEnvironmentByDefault() {
        Secrets store = Secrets.configured();
        assertSame(store, Secrets.configured(), "one store for the process");
        assertInstanceOf(EnvironmentSecrets.class, store, "a checkout with nothing configured reads environment variables");
    }

    @Test
    void theExceptionCarriesTheIdAndTheCause() {
        SecretUnavailableException plain = new SecretUnavailableException("ncbi-api-key", "not configured");
        assertEquals("ncbi-api-key", plain.getId());
        assertEquals("Secret 'ncbi-api-key': not configured", plain.getMessage());
        IllegalStateException vaultDown = new IllegalStateException("vault sealed");
        SecretUnavailableException withCause = new SecretUnavailableException("ncbi-api-key", "store cannot answer", vaultDown);
        assertSame(vaultDown, withCause.getCause(), "a store that cannot answer passes its own failure along");
        assertEquals("ncbi-api-key", withCause.getId());
    }
}
