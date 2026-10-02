/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import java.util.*;

/**
 * Serves Cohere embedding models on AWS Bedrock. Named for the wire dialect it speaks:
 * the client sends Cohere's embed request body over InvokeModel, so Bedrock embedding
 * models of other families (e.g. Titan) cannot ride this provider.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-10)
 */
public class BedrockCohereEmbeddingsProvider extends AbstractClientProvider<BedrockCohereEmbeddingsClient> implements BedrockProvider<BedrockCohereEmbeddingsClient>, ModelDiscovery {
    @Override
    public String key() {return "bedrock-cohere-embeddings";}

    /** The vendor prefix Bedrock gives Cohere's ids, and the family word inside them this client's request shape fits. */
    static final String COHERE_EMBED = "cohere.embed";

    /**
     * The client sends Cohere's embed request shape; Titan, Nova and Marengo embeddings each
     * take their own, and Cohere's rerank is not an embedding at all. A wire id outside
     * {@code cohere.embed} is listed by the account with no client in the runtime for it.
     */
    @Override
    public boolean serves(String wireModelId) {return ModelLineage.bare(wireModelId).startsWith(COHERE_EMBED);}

    @Override
    public List<DiscoveredModel> listModels() {return BedrockDiscovery.runtimeModels();}

    @Override
    protected BedrockCohereEmbeddingsClient newClient() {return new BedrockCohereEmbeddingsClient();}
}
