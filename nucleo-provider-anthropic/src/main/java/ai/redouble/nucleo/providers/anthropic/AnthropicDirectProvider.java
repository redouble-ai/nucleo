/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import ai.redouble.nucleo.secrets.*;
import com.anthropic.client.*;
import com.anthropic.client.okhttp.*;
import com.anthropic.models.models.*;
import java.util.*;

/**
 * Serves Anthropic models through Anthropic's own API. Lists what the key may call through
 * the API's model listing; the account's limits are not listed anywhere, they arrive on every
 * response's rate-limit headers, which the discovery's ping reads.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public class AnthropicDirectProvider extends AnthropicProvider<AnthropicSDKClient> implements ModelDiscovery {
    @Override
    public String key() {return "anthropic-direct";}

    @Override
    public String platform() {return "anthropic";}

    @Override
    protected String channel() {return "direct";}
    @Override
    public String credentialId() {return AnthropicSDKClient.SECRET_ID;}

    @Override
    public List<CredentialShape> credentialShapes() {return List.of(AnthropicSDKClient.SHAPE);}
    @Override
    protected AnthropicSDKClient newClient() {return new AnthropicSDKClient();}

    /** How a listed model thinks, as its capabilities state it; null for a listing that carries no capabilities. */
    private static ThinkingMode thinkingModeOf(ModelInfo info) {
        if (info.capabilities().isEmpty()) {
            return null;
        }
        ThinkingCapability thinking = info.capabilities().get().thinking();
        return thinkingMode(thinking.supported(), thinking.types().adaptive().supported(), thinking.types().enabled().supported());
    }

    /**
     * The thinking mode a model's listed capabilities amount to: {@code NONE} for a model that
     * does not think, {@code ADAPTIVE} where the model decides how much to think, which is
     * the mode a model that accepts both is called with, {@code EXTENDED} where it takes a
     * budget of the caller's only. A model listed as thinking in neither way states nothing
     * this runtime can send, and the answer is null.
     */
    static ThinkingMode thinkingMode(boolean supported, boolean adaptive, boolean enabled) {
        if (!supported) {
            return ThinkingMode.NONE;
        }
        return adaptive ? ThinkingMode.ADAPTIVE : enabled ? ThinkingMode.EXTENDED : null;
    }

    @Override
    public List<DiscoveredModel> listModels() {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(Secrets.configured().require(AnthropicSDKClient.SECRET_ID).secret())
                .maxRetries(0)
                .build();
        try {
            List<DiscoveredModel> models = new ArrayList<>();
            for (ModelInfo info : client.models().list().autoPager()) {
                models.add(DiscoveredModel.of(info.id(), info.displayName(), thinkingModeOf(info)));
            }
            return models;
        }
        finally {
            client.close();
        }
    }
}
