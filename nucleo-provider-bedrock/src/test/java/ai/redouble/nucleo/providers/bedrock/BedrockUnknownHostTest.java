/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.exception.*;
import software.amazon.awssdk.regions.*;

import java.net.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Bedrock hostname that does not resolve means the model is not served in the deployment's
 * region, because the region built the hostname. {@link BedrockClients#refineUnknownHost} turns
 * that failure into an uncorrectable that names the model and the region - never a retry signal,
 * since no retry changes DNS - and leaves every other failure untouched for the generic
 * classification.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
public class BedrockUnknownHostTest {
    private static ModelSpec spec() {
        StandardModelSpec spec = new StandardModelSpec();
        spec.setId("claude-sonnet-5-mantle");
        spec.setWireModelId("anthropic.claude-sonnet-5");
        return spec;
    }

    @Test
    void unresolvableHostBecomesUncorrectableNamingModelAndRegion() {
        UnknownHostException dns = new UnknownHostException("bedrock-mantle.us-west-1.api.aws: nodename nor servname provided, or not known");
        RuntimeException sdkFailure = SdkClientException.builder().message("Request failed").cause(dns).build();
        RuntimeException refined = BedrockClients.refineUnknownHost(sdkFailure, spec(), Region.US_WEST_1);
        assertInstanceOf(UncorrectableRuntimeLLMException.class, refined, "a nonexistent endpoint is configuration, never a retryable server error");
        assertTrue(refined.getMessage().contains("claude-sonnet-5-mantle"), "the message names the model: " + refined.getMessage());
        assertTrue(refined.getMessage().contains("us-west-1"), "the message names the region that built the hostname: " + refined.getMessage());
        assertTrue(refined.getMessage().contains("bedrock-mantle.us-west-1.api.aws"), "the message keeps the endpoint for support: " + refined.getMessage());
        assertSame(sdkFailure, refined.getCause(), "the SDK failure stays in the chain");
    }

    @Test
    void anyOtherFailureComesBackUnchanged() {
        RuntimeException plain = SdkClientException.builder().message("Request failed").cause(new java.io.IOException("connection reset")).build();
        assertSame(plain, BedrockClients.refineUnknownHost(plain, spec(), Region.US_EAST_1),
                "only an unresolvable hostname is Bedrock's to explain; everything else keeps the generic classification");
    }
}
