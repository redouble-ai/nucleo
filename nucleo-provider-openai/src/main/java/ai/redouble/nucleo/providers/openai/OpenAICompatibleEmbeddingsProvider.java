/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import ai.redouble.nucleo.secrets.*;

import java.io.*;
import java.util.*;

/**
 * Serves embedding models on an endpoint that speaks OpenAI's dialect without being OpenAI,
 * under the same credential as {@link OpenAICompatibleProvider}: one endpoint, one key, its
 * chat and embedding models told apart by the provider key of each catalog entry.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class OpenAICompatibleEmbeddingsProvider extends AbstractClientProvider<OpenAICompatibleEmbeddingsClient> implements ModelDiscovery {
    @Override
    public String key() {return "openai-compatible-embeddings";}

    @Override
    public String platform() {return "openai-compatible";}
    @Override
    public String credentialId() {return OpenAICompatibleClient.SECRET_ID;}

    @Override
    public List<CredentialShape> credentialShapes() {return List.of(OpenAICompatibleClient.SHAPE);}
    @Override
    protected OpenAICompatibleEmbeddingsClient newClient() {return new OpenAICompatibleEmbeddingsClient();}
    @Override
    public List<DiscoveredModel> listModels() throws IOException {
        Credential credential = Secrets.configured().require(credentialId());
        return OpenAIModelListing.list(credential.host(), credential.secret());
    }
}
