/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import java.util.*;

/**
 * Anthropic-family provider layer. Mirrors the client hierarchy
 * ({@code AnthropicBedrockSDKClient extends AnthropicSDKClient}) so endpoint variants share
 * Anthropic-specific construction logic as the clients diverge from other vendors. The
 * direct and Bedrock subclasses differ only in {@link #newClient()}; both get the shared
 * OkHttp connection-pool tuning here.
 *
 * @param <E> the Anthropic client type this provider builds
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public abstract class AnthropicProvider<E extends AnthropicSDKClient> extends AbstractClientProvider<E> {
    @Override
    public E createClient(ModelSpec spec) {
        E client = super.createClient(spec);
        AnthropicConnectionPools.configure(client, Settings.get(AnthropicSettings.class).connectionPoolSize);
        return client;
    }

    /** Every Anthropic-served entry, direct or Bedrock-hosted, is an {@link AnthropicModelSpec}. */
    @Override
    public Map<String, Class<? extends AbstractModelSpec>> specTypes() {
        return Map.of("anthropic", AnthropicModelSpec.class);
    }

    /** The channel suffix of this provider's catalog ids: {@code direct}, {@code bedrock}, {@code mantle}. */
    protected abstract String channel();

    /** Claude ids the Anthropic way; anything else the account lists on this surface (Bedrock lists every vendor) the generic way. */
    @Override
    public String identityOf(String wireModelId) {
        return AnthropicNaming.isClaude(wireModelId) ? AnthropicNaming.identityOf(wireModelId) : ModelLineage.identityOf(wireModelId);
    }

    /** Claude is the family the Anthropic SDK is written for, on every channel it reaches. */
    @Override
    public boolean claims(String wireModelId) {
        return AnthropicNaming.isClaude(wireModelId);
    }

    @Override
    public String catalogIdOf(String identity, String wireModelId) {
        return AnthropicNaming.isClaude(wireModelId) ? AnthropicNaming.catalogId(identity, channel()) : identity;
    }
}
