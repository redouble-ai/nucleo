/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import software.amazon.awssdk.core.exception.*;

import ai.redouble.nucleo.secrets.*;

import java.util.*;

/**
 * What every Bedrock-hosted provider says about its credentials, whichever client family it
 * builds: the AWS key pair under {@link BedrockClients#SECRET_ID}, or the identity the process
 * runs under when role-based auth is on. One answer for the four Bedrock surfaces (native
 * Anthropic, Mantle, Converse, Cohere embeddings) so a deployment cannot be configured for
 * one and not another.
 *
 * @param <E> the exact client type the provider builds
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public interface BedrockProvider<E extends Client> extends ClientProvider<E> {
    @Override
    default String credentialId() {
        return BedrockClients.SECRET_ID;
    }

    /** Two credentials, as AWS names them: the key pair and the region. */
    @Override
    default List<CredentialShape> credentialShapes() {
        return List.of(BedrockClients.KEY_PAIR_SHAPE, BedrockClients.REGION_SHAPE);
    }

    /** One platform for all four surfaces: a model disabled on base Bedrock is disabled on Mantle and Converse too. */
    @Override
    default String platform() {
        return "bedrock";
    }

    /**
     * Every Bedrock surface lists every vendor, and a model must have one identity whichever
     * surface listed it: Claude ids the Anthropic way, the rest the generic way.
     */
    @Override
    default String identityOf(String wireModelId) {
        return AnthropicNaming.isClaude(wireModelId) ? AnthropicNaming.identityOf(wireModelId) : ModelLineage.identityOf(wireModelId);
    }

    @Override
    default boolean configured() {
        return BedrockClients.configured();
    }

    @Override
    default String describeCredential() {
        return BedrockClients.describeCredentials();
    }

    /**
     * The region every Bedrock call goes to. Bedrock is regional and the catalog's {@code us.}
     * and {@code global.} profile ids resolve against this region's endpoint, so a wrong region
     * fails every call with a 400 that names neither - this fact is how a status surface makes
     * that visible before the first call. When no region is configured anywhere, the AWS chain's
     * own refusal is the fact: it names what it looked for, and hiding it would leave the row
     * blank exactly when a person needs it.
     */
    @Override
    default Map<String, String> connectionFacts() {
        try {
            return Map.of("region", BedrockClients.region().id());
        } catch (SdkClientException e) {
            return Map.of("region", e.getMessage());
        }
    }
}
