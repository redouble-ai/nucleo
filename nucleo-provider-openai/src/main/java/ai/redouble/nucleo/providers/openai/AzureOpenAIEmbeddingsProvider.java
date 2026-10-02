/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.secrets.*;

import java.util.*;

/**
 * Serves OpenAI embedding models through Azure OpenAI.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public class AzureOpenAIEmbeddingsProvider extends AbstractClientProvider<AzureOpenAIEmbeddingsClient> {
    @Override
    public String key() {return "azure-openai-embeddings";}

    @Override
    public String platform() {return "azure";}
    @Override
    public String credentialId() {return OpenAIProvider.SECRET_ID;}

    @Override
    public List<CredentialShape> credentialShapes() {return List.of(OpenAIProvider.SHAPE);}

    @Override
    protected AzureOpenAIEmbeddingsClient newClient() {return new AzureOpenAIEmbeddingsClient();}
}
