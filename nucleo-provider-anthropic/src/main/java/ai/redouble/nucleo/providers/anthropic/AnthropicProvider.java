/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.tokens.*;
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
    /** Every entry of an Anthropic provider is Anthropic's model, whichever endpoint serves it. */
    @Override
    public String vendor(ModelSpec spec) {return "anthropic";}

    /** Claude is asked to think adaptively, as the current models think, or with a budget, as the earlier ones do. */
    @Override
    public List<ThinkingMode> thinkingModes() {return List.of(ThinkingMode.ADAPTIVE, ThinkingMode.EXTENDED);}

    /** The Messages API answers 400 to a thinking type the model does not take, on Anthropic's endpoint and on Bedrock alike. */
    @Override
    public boolean refusesUnsupportedThinking() {return true;}

    @Override
    public E createClient(ModelSpec spec) {
        E client = super.createClient(spec);
        AnthropicConnectionPools.configure(client, Settings.get(AnthropicSettings.class).connectionPoolSize);
        return client;
    }

    /** Claude's counting rules, the same on every channel the Anthropic SDK reaches. */
    @Override
    public ContextEstimator contextEstimator(ModelSpec spec) {
        return AnthropicContextEstimator.INSTANCE;
    }

    @Override
    public TokenCounter tokenCounter(ModelSpec spec) {
        return AnthropicContextEstimator.COUNTER;
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
