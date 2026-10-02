/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.secrets.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link ClientProviders} registry with the providers core's own test classpath holds,
 * the test-scope fakes declared in {@code META-INF/services}: service discovery registers a
 * provider and resolves its key, an unknown key fails loud, a spec type nobody declares
 * resolves to nothing, and the app-shared client accessors guard the LLM vs embeddings family.
 * Each provider artifact proves its own discovery in its own tests.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
class ClientProvidersTest {

    @Test
    void discoversTestProviderOnClasspath() {
        // Proves drop-in discovery: a provider declared only in test scope, listed in the
        // test META-INF/services file, is registered through ServiceLoader.
        assertInstanceOf(FakeEmbeddingsProvider.class, ClientProviders.get("fake-embeddings"));
        assertInstanceOf(FakeLLMProvider.class, ClientProviders.get("fake-llm"));
    }

    /** Loading a provider puts the shapes of its credentials on record, so every store reads them as the provider declared. */
    @Test
    void loadingAProviderDeclaresItsCredentialShapes() {
        // a fresh load, whatever another test did to the registry of shapes before this one
        ClientProviders.reset();
        ClientProviders.get("fake-llm");
        CredentialShape shape = CredentialShapes.of("fake-llm-key");
        assertTrue(shape.declared(), "the provider's default shape, an API key alone, is on record");
        assertEquals("FAKE_LLM_KEY", shape.secret().variable());
        assertNull(shape.user(), "no user part");
        assertNull(shape.host(), "no host part");
    }

    @Test
    void unknownKeyFailsLoud() {
        assertThrows(UncorrectableRuntimeLLMException.class, () -> ClientProviders.get("no-such-provider"));
    }

    @Test
    void specTypesComeFromProvidersAndAnUndeclaredOneIsNull() {
        assertNull(ClientProviders.specClass("anthropic"), "no provider artifact on core's classpath declares the anthropic spec type");
        assertNull(ClientProviders.specClass("no-such-spec-type"));
    }

    @Test
    void llmClientOnEmbeddingsProviderFailsLoud() {
        StandardModelSpec embeddingsSpec = new StandardModelSpec();
        embeddingsSpec.setId("fake-emb-1");
        embeddingsSpec.setIdentity("fake-emb");
        embeddingsSpec.setProviderKey("fake-embeddings");
        embeddingsSpec.setWireModelId("fake");
        embeddingsSpec.setTpm(1000);
        embeddingsSpec.setMaxContextTokens(1000);
        embeddingsSpec.setMaxOutputTokens(100);
        // The fake provider yields an EmbeddingsClient; asking for an LLM client must throw.
        assertThrows(UncorrectableRuntimeLLMException.class,
                () -> ClientProviders.llmClient(embeddingsSpec));
        assertNotNull(ClientProviders.embeddingsClient(embeddingsSpec));
    }
}
