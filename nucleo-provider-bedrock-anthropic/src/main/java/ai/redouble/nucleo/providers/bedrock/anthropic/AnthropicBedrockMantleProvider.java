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
import java.io.*;
import java.util.*;

/**
 * Serves Anthropic models through the AWS Bedrock Mantle endpoint.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-16)
 */
public class AnthropicBedrockMantleProvider extends AnthropicProvider<AnthropicBedrockMantleSDKClient> implements BedrockProvider<AnthropicBedrockMantleSDKClient>, ModelDiscovery {
    @Override
    public String key() {return "anthropic-bedrock-mantle";}

    @Override
    protected String channel() {return "mantle";}

    /**
     * The Anthropic SDK speaks the Messages API: Claude, and nothing else. The Mantle catalog
     * lists other vendors too, and those are served by no key of the platform - the legacy
     * runtime does not know Mantle's ids - so they are reported as listed with no client.
     */
    @Override
    public boolean serves(String wireModelId) {return AnthropicNaming.isClaude(wireModelId);}

    /**
     * Mantle names models its own way (bare ids such as {@code anthropic.claude-opus-5}), which the
     * legacy runtime refuses for on-demand calls, so a Mantle entry links to no other Bedrock key.
     */
    @Override
    public String addressing() {return "bedrock-mantle";}

    @Override
    public List<DiscoveredModel> listModels() throws IOException {return BedrockDiscovery.mantleModels();}

    @Override
    protected AnthropicBedrockMantleSDKClient newClient() {return new AnthropicBedrockMantleSDKClient();}
}
