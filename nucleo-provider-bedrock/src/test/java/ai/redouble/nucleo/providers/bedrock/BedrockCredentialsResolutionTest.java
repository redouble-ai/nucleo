/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import org.junit.jupiter.api.*;
import ai.redouble.nucleo.harness.llm.*;
import software.amazon.awssdk.auth.credentials.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the decision in {@link BedrockClients#credentialsProvider()}: the store's key pair when
 * the deployment holds one, else the AWS default provider chain, so a deployment on a role or
 * a profile provisions nothing of the runtime's. The test runs on the shipped environment
 * store, where the pair is read by its declared shape from AWS's own {@code AWS_ACCESS_KEY_ID}
 * and {@code AWS_SECRET_ACCESS_KEY}: a machine exporting both has a record and signs with it, a
 * machine exporting neither, or one half, hands the SDK the chain.
 *
 * <p>{@link DefaultCredentialsProvider} resolves lazily, which is what makes this possible: building
 * one asserts nothing about the ambient identity actually existing.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-27)
 */
class BedrockCredentialsResolutionTest {

    @Test
    void theStoredPairSignsAndWithoutOneTheSdkGetsTheDefaultChain() {
        ClientProviders.all();
        boolean exported = System.getenv("AWS_ACCESS_KEY_ID") != null && System.getenv("AWS_SECRET_ACCESS_KEY") != null;
        AwsCredentialsProvider provider = BedrockClients.credentialsProvider();
        if (exported) {
            assertInstanceOf(StaticCredentialsProvider.class, provider, "AWS's own two variables, both exported, are the stored pair");
            assertTrue(BedrockClients.credentialsSource().contains("stored key"), BedrockClients.credentialsSource());
        }
        else {
            assertInstanceOf(DefaultCredentialsProvider.class, provider, "no pair in the store hands the SDK the default provider chain");
            assertTrue(BedrockClients.credentialsSource().contains("chain"),
                    "the initialization log line must say which identity the client signs with");
        }
    }

    /** The refusal names AWS's own variables and nothing AWS does not have. */
    @Test
    void theRefusalNamesAwsOwnVariablesAndNoOthers() {
        ClientProviders.all();
        String described = BedrockClients.describeCredentials();
        assertTrue(described.contains("AWS_REGION (the region every Bedrock call goes to"), described);
        assertTrue(described.contains("AWS_ACCESS_KEY_ID (the access key id) and AWS_SECRET_ACCESS_KEY (the secret access key)"), described);
        assertFalse(described.contains("AWS_REGION_") || described.contains("AWS_ACCESS_KEY_ID_"),
                "no variable AWS does not have: " + described);
        assertTrue(described.contains("default region chain") && described.contains("default credentials chain"),
                "AWS's own resolution is named beside the store: " + described);
    }
}
