/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import ai.redouble.nucleo.harness.tokens.*;
import ai.redouble.nucleo.secrets.*;

import java.io.*;
import java.util.*;

/**
 * Serves chat models on an endpoint that speaks OpenAI's Chat Completions API without being
 * OpenAI, through the framework's own HTTP client; {@link OpenAICompatibleResponsesProvider} is
 * the same for an endpoint that speaks the Responses API. One credential names the endpoint:
 * {@code secret} is the bearer token and {@code host} the API root. The catalog fragments carry
 * no entries for this provider, since the models behind such an endpoint are the deployment's
 * to know; its entries live in the deployment's own {@code models.json}, and the discovery lists
 * what the endpoint answers under {@code GET /models}. A deployment with several such endpoints
 * subclasses this provider once per endpoint, with its own key and credential id.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class OpenAICompatibleProvider extends AbstractClientProvider<OpenAICompatibleClient> implements ModelDiscovery {
    @Override
    public String key() {return "openai-compatible";}

    @Override
    public String platform() {return "openai-compatible";}
    @Override
    public String credentialId() {return OpenAICompatibleClient.SECRET_ID;}

    @Override
    public List<CredentialShape> credentialShapes() {return List.of(OpenAICompatibleClient.SHAPE);}
    /** The endpoint's wire shape is OpenAI's; the models behind it are not, so text counts at the neutral density. */
    @Override
    public ContextEstimator contextEstimator(ModelSpec spec) {return OpenAIContextEstimator.COMPATIBLE;}
    @Override
    protected OpenAICompatibleClient newClient() {return new OpenAICompatibleClient(WireApi.CHAT_COMPLETIONS);}
    @Override
    public List<DiscoveredModel> listModels() throws IOException {
        Credential credential = Secrets.configured().require(credentialId());
        return OpenAIModelListing.list(credential.host(), credential.secret());
    }
}
