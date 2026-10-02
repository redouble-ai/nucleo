/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.spring;

import ai.redouble.nucleo.secrets.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The store reads a credential's three parts from {@code nucleo.credentials.<id>}, its
 * {@code .user} and its {@code .host}, each falling back to the environment variable of the
 * runtime's one rule; an empty value is no value; an id nothing binds anywhere is null; and a
 * store asked before the context has handed it the environment refuses.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class SpringSecretsTest {

    @AfterEach
    void detach() {
        new SpringSecrets().setEnvironment(null);
    }

    @Test
    void thePartsComeFromTheThreeProperties() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("nucleo.credentials.anthropic-api-key", "sk-test")
                .withProperty("nucleo.credentials.aws-access-key-id.user", "AKIA")
                .withProperty("nucleo.credentials.aws-access-key-id", "secret")
                .withProperty("nucleo.credentials.openai-compatible-api-key.host", "https://api.example.test/v1")
                .withProperty("nucleo.credentials.openai-api-key", "");
        SpringSecrets store = new SpringSecrets();
        store.setEnvironment(env);
        Credential anthropic = store.find("anthropic-api-key");
        assertEquals("sk-test", anthropic.secret());
        assertNull(anthropic.user());
        Credential aws = store.find("aws-access-key-id");
        assertEquals("AKIA", aws.user());
        assertEquals("secret", aws.secret());
        assertEquals("https://api.example.test/v1", store.find("openai-compatible-api-key").host(), "a host alone is a credential");
        assertNull(store.find("openai-api-key"), "an empty binding is no binding");
        assertNull(store.find("epo-api-key"), "nothing bound is null, never an empty credential");
        assertTrue(store.describe("epo-api-key").contains("nucleo.credentials.epo-api-key"), "a refusal names the property to bind");
        assertTrue(store.describe("epo-api-key").contains("EPO_API_KEY"));
    }

    /**
     * A part no property binds comes from the environment variable of the runtime's one rule,
     * part by part: an application that binds nothing runs with the variables exported, a
     * bound secret and an exported host compose into one credential, and a bound part is never
     * overridden by a variable.
     */
    @Test
    void aPartNoPropertyBindsComesFromTheEnvironmentVariableOfTheSameName() {
        Map<String, String> exported = Map.of(
                "AZURE_FOUNDRY_API_KEY", "exported-key", "AZURE_FOUNDRY_API_KEY_HOST", "exported.services.ai.azure.com",
                "AWS_REGION", "us-east-1",
                "OPENAI_COMPATIBLE_API_KEY_HOST", "https://exported.example.test/v1",
                "ANTHROPIC_API_KEY", "exported-anthropic");
        MockEnvironment env = new MockEnvironment()
                .withProperty("nucleo.credentials.openai-compatible-api-key", "bound-token")
                .withProperty("nucleo.credentials.anthropic-api-key", "bound-anthropic");
        SpringSecrets store = new SpringSecrets(exported::get);
        store.setEnvironment(env);
        Credential azure = store.find("azure-foundry-api-key");
        assertEquals("exported-key", azure.secret(), "nothing bound: the variable answers");
        assertEquals("exported.services.ai.azure.com", azure.host(), "and its _HOST suffix answers the host");
        assertEquals("us-east-1", store.find("aws-region").secret(), "the region too");
        Credential compatible = store.find("openai-compatible-api-key");
        assertEquals("bound-token", compatible.secret(), "the bound part");
        assertEquals("https://exported.example.test/v1", compatible.host(), "composed with the exported part");
        assertEquals("bound-anthropic", store.find("anthropic-api-key").secret(), "a bound part wins over a variable");
        assertNull(store.find("epo-api-key"), "nothing anywhere is null");
        assertTrue(store.describe("epo-api-key").contains("nucleo.credentials.epo-api-key")
                        && store.describe("epo-api-key").contains("EPO_API_KEY"),
                "a refusal names both ways to provide it: " + store.describe("epo-api-key"));
    }

    /**
     * A declared shape decides what the store reads and asks for: a pair declared under the
     * vendor's own two variable names is read from exactly those, a single value from its one
     * variable, and the refusal names those and no suffix a credential does not have. The ids
     * here are the test's own, so the declarations collide with no provider's.
     */
    @Test
    void aDeclaredShapeDecidesWhatIsReadAndAskedFor() {
        CredentialShapes.declare(CredentialShape.userAndSecret("acme-pair", "ACME_KEY_ID", "the key id", "ACME_KEY_SECRET", "the key secret"));
        CredentialShapes.declare(CredentialShape.secret("acme-region", "ACME_REGION", "the region every call goes to"));
        Map<String, String> exported = Map.of("ACME_KEY_ID", "AKIA", "ACME_KEY_SECRET", "s3cr3t", "ACME_REGION", "us-east-1",
                "ACME_REGION_HOST", "never read");
        SpringSecrets store = new SpringSecrets(exported::get);
        store.setEnvironment(new MockEnvironment());
        Credential pair = store.find("acme-pair");
        assertEquals("AKIA", pair.user());
        assertEquals("s3cr3t", pair.secret());
        Credential region = store.find("acme-region");
        assertEquals("us-east-1", region.secret());
        assertNull(region.host(), "a part the shape does not have is never read");
        String described = store.describe("acme-pair");
        assertTrue(described.contains("nucleo.credentials.acme-pair.user (the key id)"), described);
        assertTrue(described.contains("ACME_KEY_ID (the key id) and ACME_KEY_SECRET (the key secret)"), described);
        assertFalse(described.contains("_USER") || described.contains("_HOST"), "no suffix the credential does not have: " + described);
        String regionText = store.describe("acme-region");
        assertTrue(regionText.contains("the property nucleo.credentials.acme-region (the region every call goes to)"), regionText);
        assertTrue(regionText.contains("the environment variable ACME_REGION (the region every call goes to)"), regionText);
        assertFalse(regionText.contains("ACME_REGION_"), "ACME_REGION_USER and ACME_REGION_HOST are not things: " + regionText);
    }

    @Test
    void aStoreAskedBeforeTheContextRefuses() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new SpringSecrets().find("anthropic-api-key"));
        assertTrue(ex.getMessage().contains("anthropic-api-key"));
    }
}
