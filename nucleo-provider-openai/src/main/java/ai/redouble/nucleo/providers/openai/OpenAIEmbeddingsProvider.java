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
 * Serves OpenAI embedding models through OpenAI's own client.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public class OpenAIEmbeddingsProvider extends AbstractClientProvider<OpenAISDKEmbeddingsClient> implements ModelDiscovery {
    @Override
    public String key() {return "openai-embeddings";}

    @Override
    public String platform() {return "openai";}
    @Override
    public String credentialId() {return OpenAIProvider.SECRET_ID;}

    @Override
    public List<CredentialShape> credentialShapes() {return List.of(OpenAIProvider.SHAPE);}
    @Override
    protected OpenAISDKEmbeddingsClient newClient() {return new OpenAISDKEmbeddingsClient();}
    @Override
    public List<DiscoveredModel> listModels() throws IOException {
        return OpenAIModelListing.list(OpenAIModelListing.OPENAI_ROOT, Secrets.configured().require(OpenAIProvider.SECRET_ID).secret());
    }
}
