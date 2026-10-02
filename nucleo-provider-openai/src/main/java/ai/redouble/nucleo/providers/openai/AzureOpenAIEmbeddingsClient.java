/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.secrets.*;
import org.slf4j.*;

import java.io.*;

/**
 * Azure OpenAI embeddings client with rate limiting support.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-06-20)
 */
public class AzureOpenAIEmbeddingsClient extends AbstractEmbeddingsClient {
    private static final Logger log = LoggerFactory.getLogger(AzureOpenAIEmbeddingsClient.class);
    static final String SERVICE_NAME = "Azure OpenAI";
    private static final String host;
    static {
        try {
            host = Secrets.configured().require(OpenAIProvider.SECRET_ID).host();
            log.info("Using host: {} for Azure OpenAI embeddings API", host);
        }
        catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException("Could not initialize Azure OpenAI secret", e);
        }
    }
    private final OpenAIDialectEndpoint endpoint;

    public AzureOpenAIEmbeddingsClient() {
        // The provider assigns the spec right after construction (AbstractClientProvider).
        // The host normalizes like every Azure resource: a person's pasted endpoint, scheme
        // and path included, addresses the same resource as the bare hostname.
        this.endpoint = new OpenAIDialectEndpoint(SERVICE_NAME,
                "https://" + AzureFoundryOpenAIClient.resourceHost(host), Secrets.configured().require(OpenAIProvider.SECRET_ID).secret());
    }

    @Override
    protected RawEmbedding doCalculateEmbedding(String input, EmbeddingPurpose purpose) throws IOException {
        // Delegate to the OpenAI client's protected doCalculateEmbedding, never its
        // public calculateEmbedding: dimension coercion and rate limiting run in the
        // final template method on this Azure instance, so routing through the public
        // path would coerce and rate-limit twice.
        OpenAICompatibleEmbeddingsClient delegate = new OpenAICompatibleEmbeddingsClient(endpoint,
                "/openai/deployments/text-embedding-3-small/embeddings?api-version=2023-05-15", getModel());
        return delegate.doCalculateEmbedding(input, purpose);
    }

    @Override
    protected boolean is429Error(Exception e) {
        return OpenAIDialectFailures.is429(e);
    }

    @Override
    protected RateLimitInfo extractRateLimitInfo(Exception e) {
        if (!OpenAIDialectFailures.is429(e)) {
            return null;
        }
        return new RateLimitInfo(model != null ? model.getId() : "unknown", null, null, null, null, null,
                OpenAIDialectFailures.retryAfter(e), true, RateLimitType.CAPACITY);
    }
}
