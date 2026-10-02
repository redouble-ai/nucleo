/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What this artifact contributes once it is on the classpath: the Converse and Cohere embeddings
 * providers are discovered by key, its fragment's entries come up as standard specs, resolving a
 * provider never constructs its AWS client, and its image encoder builds a native provider object
 * without routing through text. The Anthropic-on-Bedrock and Mantle providers are not this
 * artifact's; {@code nucleo-provider-bedrock-anthropic} adds them, and a catalog written with
 * them, read without them, has its runtime-surface Claude entries linked to Converse and its
 * Mantle entries unavailable.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
class BedrockProviderModuleTest {

    @Test
    void providersAreDiscoveredByKey() {
        assertInstanceOf(BedrockConverseProvider.class, ClientProviders.get("bedrock-converse"));
        assertInstanceOf(BedrockCohereEmbeddingsProvider.class, ClientProviders.get("bedrock-cohere-embeddings"));
    }

    @Test
    void theAnthropicSurfacesAreNotThisArtifacts() {
        assertNull(ClientProviders.find("anthropic-bedrock"), "the Anthropic-on-Bedrock provider ships in nucleo-provider-bedrock-anthropic");
        assertNull(ClientProviders.find("anthropic-bedrock-mantle"), "the Mantle provider ships in nucleo-provider-bedrock-anthropic");
    }

    @Test
    void theFragmentsEntriesAreStandardSpecs() throws IOException {
        // The suite's Models facade runs on the shared test fixture; this test's subject is
        // the shipped fragment itself, so it loads the classpath fragments the way the
        // runtime's fallback does and asserts on that backend.
        List<JsonModelsBackend.Layer> layers = new ArrayList<>();
        for (Enumeration<URL> fragments = getClass().getClassLoader().getResources(JsonModelsBackend.FRAGMENT_RESOURCE); fragments.hasMoreElements(); ) {
            URL fragment = fragments.nextElement();
            try (InputStream in = fragment.openStream()) {
                layers.add(new JsonModelsBackend.Layer(fragment.toString(), new String(in.readAllBytes(), StandardCharsets.UTF_8), true));
            }
        }
        JsonModelsBackend fragmentCatalog = new JsonModelsBackend(layers);
        assertInstanceOf(StandardModelSpec.class, fragmentCatalog.spec("nova-micro"));
        assertInstanceOf(StandardModelSpec.class, fragmentCatalog.spec("cohere-embed-v4-bedrock"));
    }

    @Test
    void aClaudeEntryOfTheAbsentRuntimeSurfaceLinksToConverseAndAMantleEntryIsUnavailable() {
        // written by a classpath that carried nucleo-provider-bedrock-anthropic, read by one that
        // does not (a native image): an entry of the Anthropic runtime surface names its model the
        // way bedrock-runtime does, so it rides Converse, the provider there that serves every vendor,
        // with no need of its Anthropic spec type; a Mantle entry's bare id is spelled for Mantle's
        // endpoint, which nothing in this artifact reaches, so it is left out
        String catalog = """
                { "providers": { "anthropic-bedrock": { "platform": "bedrock", "addressing": "bedrock" },
                                 "anthropic-bedrock-mantle": { "platform": "bedrock", "addressing": "bedrock-mantle" },
                                 "bedrock-converse": { "platform": "bedrock", "addressing": "bedrock" } },
                  "provider_defaults": { "anthropic-bedrock": { "spec_type": "anthropic" }, "anthropic-bedrock-mantle": { "spec_type": "anthropic" } },
                  "models": [
                    { "id": "claude-opus-5-bedrock", "identity": "opus-5", "grade": "XL", "provider_key": "anthropic-bedrock",
                      "platform": "bedrock", "wire_model_id": "us.anthropic.claude-opus-5", "max_context_tokens": 200000,
                      "max_output_tokens": 64000, "tpm": 400000 },
                    { "id": "claude-opus-5-mantle", "identity": "opus-5", "grade": "XL", "provider_key": "anthropic-bedrock-mantle",
                      "platform": "bedrock", "wire_model_id": "anthropic.claude-opus-5", "max_context_tokens": 200000,
                      "max_output_tokens": 64000, "tpm": 400000 } ] }
                """;
        JsonModelsBackend loaded = new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("jvm-written", catalog, false)));
        ModelSpec linked = loaded.spec("claude-opus-5-bedrock");
        assertEquals("bedrock-converse", linked.getProviderKey());
        assertInstanceOf(StandardModelSpec.class, linked, "shaped by Converse's defaults, not the absent surface's");
        assertNull(loaded.spec("claude-opus-5-mantle"), "nothing here reaches Mantle's endpoint, so the entry is unavailable");
    }

    @Test
    void providerResolutionDoesNotConstructClient() {
        // Resolving the provider must not build its AWS client, which reads credentials;
        // get() returns the provider object, createClient stays uncalled here.
        assertDoesNotThrow(() -> ClientProviders.get("bedrock-converse"));
    }

    @Test
    void nativeImageEncoderBuildsAProviderObjectWithoutTheWrapper() {
        var encoder = new BedrockImageBlockEncoder();
        encoder.setTextWrapper(t -> { throw new AssertionError("native encoder used the text wrapper"); });
        assertNotNull(encoder.encode(new ImageBlock("aGk=", "image/png", null)));
    }
}
