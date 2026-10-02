/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link BedrockCohereEmbeddingsClient} without any transport: the Cohere request
 * body (input_type per purpose, pinned output_dimension, explicit truncate), both documented
 * response shapes plus the loud failure on an unrecognized one, the pinned-dimension spec
 * contract, and 429/5xx classification through the IOException wrap (the SDK exception sits
 * in the cause chain, never at the top level).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-10)
 */
class BedrockCohereEmbeddingsClientTest {

    private static StandardModelSpec spec(Integer dims) {
        StandardModelSpec s = new StandardModelSpec();
        s.setId("cohere-test");
        s.setIdentity("cohere-test");
        s.setProviderKey("bedrock-cohere-embeddings");
        s.setWireModelId("us.cohere.embed-v4:0");
        s.setMaxContextTokens(128000);
        s.setMaxOutputTokens(128000);
        s.setTpm(300000);
        s.setRpm(2000);
        s.setEmbeddingDimensions(dims);
        return s;
    }

    @Test
    void requestBodyCarriesTheFullCohereContract() throws IOException {
        JsonNode doc = NucleoJsonSerializer.readTree(BedrockCohereEmbeddingsClient.buildRequestBody("hello", EmbeddingPurpose.DOCUMENT, 1024));
        assertEquals("search_document", doc.get("input_type").asText());
        assertEquals(1, doc.get("texts").size());
        assertEquals("hello", doc.get("texts").get(0).asText());
        assertEquals("float", doc.get("embedding_types").get(0).asText());
        assertEquals(1024, doc.get("output_dimension").asInt());
        assertEquals("NONE", doc.get("truncate").asText());
        JsonNode query = NucleoJsonSerializer.readTree(BedrockCohereEmbeddingsClient.buildRequestBody("probe", EmbeddingPurpose.QUERY, 512));
        assertEquals("search_query", query.get("input_type").asText());
        assertEquals(512, query.get("output_dimension").asInt());
    }

    @Test
    void parsesTheFloatsResponseShape() throws IOException {
        float[] v = BedrockCohereEmbeddingsClient.parseEmbedding(
                "{\"id\":\"x\",\"response_type\":\"embeddings_floats\",\"embeddings\":[[0.25,-0.5,1.0]]}");
        assertArrayEquals(new float[]{0.25f, -0.5f, 1.0f}, v);
    }

    @Test
    void parsesTheByTypeResponseShape() throws IOException {
        float[] v = BedrockCohereEmbeddingsClient.parseEmbedding(
                "{\"id\":\"x\",\"response_type\":\"embeddings_by_type\",\"embeddings\":{\"float\":[[0.125,0.75]]}}");
        assertArrayEquals(new float[]{0.125f, 0.75f}, v);
    }

    @Test
    void unrecognizedShapeFailsNamingTheReceivedResponseType() {
        IOException e = assertThrows(IOException.class, () -> BedrockCohereEmbeddingsClient.parseEmbedding(
                "{\"response_type\":\"embeddings_by_type\",\"embeddings\":{\"int8\":[[1,2]]}}"));
        assertTrue(e.getMessage().contains("embeddings_by_type"), e.getMessage());
        assertThrows(IOException.class, () -> BedrockCohereEmbeddingsClient.parseEmbedding("{\"embeddings\":[]}"));
        assertThrows(IOException.class, () -> BedrockCohereEmbeddingsClient.parseEmbedding("{\"id\":\"x\"}"));
    }

    @Test
    void specWithoutPinnedDimensionIsRejected() {
        BedrockCohereEmbeddingsClient client = new BedrockCohereEmbeddingsClient(null);
        assertThrows(UncorrectableRuntimeLLMException.class, () -> client.setModel(spec(null)));
        assertDoesNotThrow(() -> client.setModel(spec(1024)));
    }

    @Test
    void classifies429ByStatusCodeNeverByMessage() {
        BedrockCohereEmbeddingsClient client = new BedrockCohereEmbeddingsClient(null);
        // the Cohere-on-Bedrock phrasing that message needles missed in production:
        // the status code is the classification, wording is irrelevant
        assertTrue(client.is429Error(new IOException(ThrottlingException.builder()
                .message("Too many tokens, please wait before trying again.").statusCode(429).build())));
        assertTrue(client.is429Error(new IOException(BedrockRuntimeException.builder()
                .message("anything at all").statusCode(429).build())));
        assertFalse(client.is429Error(new IOException(BedrockRuntimeException.builder()
                .message("rate limit throttling too many requests").statusCode(400).build())));
        assertFalse(client.is429Error(new IOException("ordinary failure")));
    }

    @Test
    void classifiesServerErrorsByStatusCode() {
        BedrockCohereEmbeddingsClient client = new BedrockCohereEmbeddingsClient(null);
        assertTrue(client.isServerError(new IOException(InternalServerException.builder().message("x").statusCode(500).build())));
        assertTrue(client.isServerError(new IOException(ServiceUnavailableException.builder().message("x").statusCode(503).build())));
        assertTrue(client.isServerError(new IOException(ModelTimeoutException.builder().message("x").statusCode(408).build())));
        assertFalse(client.isServerError(new IOException(BedrockRuntimeException.builder().message("x").statusCode(400).build())));
        assertFalse(client.isServerError(new IOException("ordinary failure")));
    }
}
