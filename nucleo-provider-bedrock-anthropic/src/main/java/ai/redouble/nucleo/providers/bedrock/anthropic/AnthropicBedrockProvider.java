/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock.anthropic;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import ai.redouble.nucleo.providers.anthropic.*;
import ai.redouble.nucleo.providers.bedrock.*;
import java.util.*;

/**
 * Serves Anthropic models through AWS Bedrock.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public class AnthropicBedrockProvider extends AnthropicProvider<AnthropicBedrockSDKClient> implements BedrockProvider<AnthropicBedrockSDKClient>, ModelDiscovery {
    @Override
    public String key() {return "anthropic-bedrock";}

    @Override
    protected String channel() {return "bedrock";}

    /** The Anthropic SDK speaks the Messages API: Claude, and nothing else the account lists. */
    @Override
    public boolean serves(String wireModelId) {return AnthropicNaming.isClaude(wireModelId);}

    @Override
    public List<DiscoveredModel> listModels() {return BedrockDiscovery.runtimeModels();}

    @Override
    protected AnthropicBedrockSDKClient newClient() {return new AnthropicBedrockSDKClient();}
}
