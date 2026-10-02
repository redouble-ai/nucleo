/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What this artifact contributes once it is on the classpath: its providers are discovered by
 * key, its catalog fragment loads, and its image encoder builds a native provider object
 * without routing through text.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
class OpenAIProviderModuleTest {

    @Test
    void providersAreDiscoveredByKey() {
        assertInstanceOf(OpenAIProvider.class, ClientProviders.get("openai"));
        assertInstanceOf(OpenAIEmbeddingsProvider.class, ClientProviders.get("openai-embeddings"));
        assertInstanceOf(AzureOpenAIEmbeddingsProvider.class, ClientProviders.get("azure-openai-embeddings"));
        assertInstanceOf(OpenAICompatibleProvider.class, ClientProviders.get("openai-compatible"));
        assertInstanceOf(OpenAICompatibleEmbeddingsProvider.class, ClientProviders.get("openai-compatible-embeddings"));
        assertEquals("openai-compatible-api-key", ClientProviders.get("openai-compatible").credentialId());
        // The wire API is a surface, so a provider: each transport comes as a pair, one per dialect
        assertInstanceOf(OpenAIChatCompletionsProvider.class, ClientProviders.get("openai-chat-completions"));
        assertInstanceOf(OpenAICompatibleResponsesProvider.class, ClientProviders.get("openai-compatible-responses"));
        assertEquals(WireApi.RESPONSES, ((AbstractOpenAIChatClient) ClientProviders.get("openai").createClient(Models.spec("gpt-5"))).wireApi());
        assertEquals(WireApi.CHAT_COMPLETIONS, ((AbstractOpenAIChatClient) ClientProviders.get("openai-chat-completions").createClient(Models.spec("gpt-5"))).wireApi());
        assertInstanceOf(AzureFoundryOpenAIProvider.class, ClientProviders.get("azure-foundry-openai"));
        assertEquals("azure-foundry-api-key", ClientProviders.get("azure-foundry-openai").credentialId());
        assertEquals("azure", ClientProviders.get("azure-foundry-openai").platform());
        assertInstanceOf(ModelDiscovery.class, ClientProviders.get("azure-foundry-openai"), "a Foundry resource lists its deployments");
    }

    @Test
    void theFragmentCarriesThisProvidersModels() {
        assertEquals("openai", Models.spec("gpt-5").getProviderKey());
        assertEquals("openai-embeddings", Models.spec("text-embedding-3-small").getProviderKey());
    }

    @Test
    void nativeImageEncoderBuildsAProviderObjectWithoutTheWrapper() {
        var encoder = new OpenAIImageBlockEncoder();
        encoder.setTextWrapper(t -> { throw new AssertionError("native encoder used the text wrapper"); });
        assertNotNull(encoder.encode(new ImageBlock("aGk=", "image/png", null)));
    }
}
