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
 * A credential's owner declares its shape once - which parts it has and the environment
 * variable each reads from - and every store reads and describes the credential by that
 * declaration, so the variables the runtime reads are exactly the ones a person is asked
 * for: AWS's own names for the Bedrock pair, no {@code _USER} or {@code _HOST} a credential
 * does not have. An id nobody declared follows the one rule; declaring an id twice with
 * different shapes is refused.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-23)
 */
class CredentialShapesTest {

    @AfterEach
    void forget() {
        CredentialShapes.reset();
    }

    @Test
    void aDeclaredShapeIsReadUnderItsOwnVariablesAndNothingElse() {
        CredentialShapes.declare(CredentialShape.userAndSecret("aws-access-key-id", "AWS_ACCESS_KEY_ID", "the access key id",
                "AWS_SECRET_ACCESS_KEY", "the secret access key"));
        CredentialShapes.declare(CredentialShape.secret("aws-region", "AWS_REGION", "the region"));
        Map<String, String> exported = Map.of("AWS_ACCESS_KEY_ID", "AKIA", "AWS_SECRET_ACCESS_KEY", "s3cr3t", "AWS_REGION", "us-east-1",
                "AWS_REGION_HOST", "never read", "AWS_ACCESS_KEY_ID_USER", "never read");
        Secrets secrets = new EnvironmentSecrets(exported::get);
        Credential aws = secrets.require("aws-access-key-id");
        assertEquals("AKIA", aws.user(), "the user half is AWS's own variable for the key id");
        assertEquals("s3cr3t", aws.secret(), "the secret half is AWS's own variable for the secret key");
        assertNull(aws.host(), "a part the shape does not have is never read");
        Credential region = secrets.require("aws-region");
        assertEquals("us-east-1", region.secret());
        assertNull(region.host(), "AWS_REGION_HOST is not a thing, and is not read");
        assertEquals("the environment variables AWS_ACCESS_KEY_ID (the access key id) and AWS_SECRET_ACCESS_KEY (the secret access key)",
                secrets.describe("aws-access-key-id"));
        assertEquals("the environment variable AWS_REGION (the region)", secrets.describe("aws-region"));
    }

    @Test
    void aSecretAndHostShapeDescribesBothParts() {
        CredentialShapes.declare(CredentialShape.secretAndHost("azure-foundry-api-key", "AZURE_FOUNDRY_API_KEY", "the resource key",
                "AZURE_FOUNDRY_API_KEY_HOST", "the resource hostname"));
        Secrets secrets = new EnvironmentSecrets(Map.of("AZURE_FOUNDRY_API_KEY_HOST", "r.services.ai.azure.com")::get);
        Credential azure = secrets.require("azure-foundry-api-key");
        assertNull(azure.secret());
        assertEquals("r.services.ai.azure.com", azure.host(), "a host alone is a credential, as before");
        assertEquals("the environment variables AZURE_FOUNDRY_API_KEY (the resource key) and AZURE_FOUNDRY_API_KEY_HOST (the resource hostname)",
                secrets.describe("azure-foundry-api-key"));
        assertEquals("the properties nucleo.credentials.azure-foundry-api-key (the resource key) and nucleo.credentials.azure-foundry-api-key.host (the resource hostname)",
                CredentialShapes.of("azure-foundry-api-key").describeProperties("nucleo.credentials."));
    }

    /** A server that checks no key is reached by its address alone: the one part read, and the only one described. */
    @Test
    void aHostShapeIsTheAddressAlone() {
        CredentialShapes.declare(CredentialShape.host("systemone-local", "SYSTEMONE_LOCAL_HOST", "the address of the server"));
        Secrets secrets = new EnvironmentSecrets(Map.of("SYSTEMONE_LOCAL_HOST", "http://127.0.0.1:8009", "SYSTEMONE_LOCAL", "never read")::get);
        Credential local = secrets.require("systemone-local");
        assertEquals("http://127.0.0.1:8009", local.host());
        assertNull(local.secret(), "a host shape has no secret, and none is read");
        assertNull(local.user());
        assertEquals("the environment variable SYSTEMONE_LOCAL_HOST (the address of the server)", secrets.describe("systemone-local"));
        assertEquals("the property nucleo.credentials.systemone-local.host (the address of the server)",
                CredentialShapes.of("systemone-local").describeProperties("nucleo.credentials."));
    }

    @Test
    void anUndeclaredIdFollowsTheOneRule() {
        CredentialShape generic = CredentialShapes.of("epo-api-key");
        assertFalse(generic.declared());
        assertEquals("EPO_API_KEY", generic.secret().variable());
        assertEquals("EPO_API_KEY_USER", generic.user().variable());
        assertEquals("EPO_API_KEY_HOST", generic.host().variable());
        assertEquals("the environment variable EPO_API_KEY (and EPO_API_KEY_USER / EPO_API_KEY_HOST when the credential has a user or host part)",
                generic.describeVariables());
        assertEquals("the property nucleo.credentials.epo-api-key (with nucleo.credentials.epo-api-key.user / nucleo.credentials.epo-api-key.host"
                + " when the credential has a user or host part)", generic.describeProperties("nucleo.credentials."));
    }

    @Test
    void theSameShapeMayBeDeclaredTwiceButADifferentOneIsRefused() {
        CredentialShape shape = CredentialShape.secret("openai-api-key", "OPENAI_API_KEY", "the API key");
        CredentialShapes.declare(shape);
        CredentialShapes.declare(CredentialShape.secret("openai-api-key", "OPENAI_API_KEY", "the API key"));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> CredentialShapes.declare(CredentialShape.secretAndHost("openai-api-key", "OPENAI_API_KEY", "the API key", "OPENAI_API_KEY_HOST", "the host")));
        assertTrue(refused.getMessage().contains("openai-api-key"), refused.getMessage());
        assertEquals(Set.of("openai-api-key"), CredentialShapes.declared().keySet());
    }

    @Test
    void aShapeNeedsAtLeastOnePartAndEveryPartItsWords() {
        assertThrows(IllegalArgumentException.class, () -> new CredentialShape("x", null, null, null, true));
        assertThrows(IllegalArgumentException.class, () -> new CredentialShape.Part("X", " "));
    }
}
