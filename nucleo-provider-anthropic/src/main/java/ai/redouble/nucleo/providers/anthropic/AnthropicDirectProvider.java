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

    @Override
    public List<DiscoveredModel> listModels() {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(Secrets.configured().require(AnthropicSDKClient.SECRET_ID).secret())
                .maxRetries(0)
                .build();
        try {
            List<DiscoveredModel> models = new ArrayList<>();
            for (ModelInfo info : client.models().list().autoPager()) {
                models.add(DiscoveredModel.of(info.id(), info.displayName()));
            }
            return models;
        }
        finally {
            client.close();
        }
    }
}
