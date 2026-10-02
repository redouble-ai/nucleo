/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import java.util.*;

/**
 * Serves non-Anthropic Bedrock models (Nova, Llama, Mistral, Titan) through the Bedrock
 * Converse API.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public class BedrockConverseProvider extends AbstractClientProvider<BedrockConverseClient> implements BedrockProvider<BedrockConverseClient>, ModelDiscovery {
    @Override
    public String key() {return "bedrock-converse";}

    /** Converse serves every vendor; its Claude entries are named the Anthropic way on the converse channel. */
    @Override
    public String identityOf(String wireModelId) {
        return AnthropicNaming.isClaude(wireModelId) ? AnthropicNaming.identityOf(wireModelId) : ModelLineage.identityOf(wireModelId);
    }

    @Override
    public String catalogIdOf(String identity, String wireModelId) {
        return AnthropicNaming.isClaude(wireModelId) ? AnthropicNaming.catalogId(identity, "converse") : identity;
    }
    @Override
    public List<DiscoveredModel> listModels() {return BedrockDiscovery.runtimeModels();}

    @Override
    protected BedrockConverseClient newClient() {return new BedrockConverseClient();}
}
