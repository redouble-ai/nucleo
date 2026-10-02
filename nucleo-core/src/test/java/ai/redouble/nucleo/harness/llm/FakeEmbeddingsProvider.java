/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.models.*;

import java.io.*;

/**
 * Test-only provider that builds a no-secret {@link EmbeddingsClient}, so tests can exercise
 * provider resolution and the {@code ClientProviders.llmClient}/{@code embeddingsClient} guarded cast
 * without real credentials. Discovered by the classpath scan like any provider.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public class FakeEmbeddingsProvider extends AbstractClientProvider<FakeEmbeddingsClient> {
    @Override
    public String key() {return "fake-embeddings";}

    @Override
    public String platform() {return "fake";}
    @Override
    public String credentialId() {return "fake-embeddings-key";}
    @Override
    public boolean configured() {return true;}

    @Override
    protected FakeEmbeddingsClient newClient() {return new FakeEmbeddingsClient();}
}

class FakeEmbeddingsClient implements EmbeddingsClient {
    private ModelSpec model;

    @Override
    public EmbeddingsResponse embed(String input, EmbeddingPurpose purpose) throws IOException, InterruptedException {
        EmbeddingsResponse response = new EmbeddingsResponse(model, purpose);
        response.setVector(new float[0]);
        response.setSuccessful(true);
        return response;
    }

    @Override
    public ModelSpec getModel() {return model;}

    @Override
    public void setModel(ModelSpec model) {this.model = model;}
}
