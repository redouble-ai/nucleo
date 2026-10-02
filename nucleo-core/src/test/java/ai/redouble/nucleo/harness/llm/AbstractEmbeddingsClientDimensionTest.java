/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the spec-pinned dimension seam in {@link AbstractEmbeddingsClient}: a spec that
 * declares {@code embeddingDimensions} is coerced to that width (native vectors are only
 * normalized, never expand/folded against the canonical dimension), while a spec without
 * it is coerced to the canonical {@link EmbeddingsClient#DIMENSIONS} width.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-10)
 */
class AbstractEmbeddingsClientDimensionTest {

    static class FixedVectorClient extends AbstractEmbeddingsClient {
        private final float[] raw;

        FixedVectorClient(float[] raw) {this.raw = raw;}

        @Override
        protected RawEmbedding doCalculateEmbedding(String input, EmbeddingPurpose purpose) {return new RawEmbedding(raw.clone(), 7);}

        @Override
        protected boolean is429Error(Exception e) {return false;}

        @Override
        protected RateLimitInfo extractRateLimitInfo(Exception e) {return null;}
    }

    private static StandardModelSpec spec(Integer dims) {
        StandardModelSpec s = new StandardModelSpec();
        s.setId("dim-test-" + dims);
        s.setIdentity("dim-test");
        s.setProviderKey("fake-embeddings");
        s.setWireModelId("dim-test");
        s.setMaxContextTokens(1000);
        s.setMaxOutputTokens(100);
        s.setTpm(1000);
        s.setRpm(100);
        s.setEmbeddingDimensions(dims);
        return s;
    }

    @Test
    void specPinnedDimensionSkipsTheCanonicalCoercion() throws IOException, InterruptedException {
        FixedVectorClient client = new FixedVectorClient(new float[]{3f, 4f, 0f, 0f});
        client.setModel(spec(4));
        float[] out = client.calculateEmbedding("t", EmbeddingPurpose.DOCUMENT);
        // native width preserved, values normalized in place - no expand to 6, no fold back
        assertEquals(4, out.length);
        assertArrayEquals(new float[]{0.6f, 0.8f, 0f, 0f}, out, 1e-6f);
    }

    @Test
    void specWithoutDimensionKeepsTheCanonicalCoercion() throws IOException, InterruptedException {
        FixedVectorClient client = new FixedVectorClient(new float[]{1f, 0f, 0f, 0f});
        client.setModel(spec(null));
        float[] out = client.calculateEmbedding("t", EmbeddingPurpose.DOCUMENT);
        assertEquals(EmbeddingsClient.DIMENSIONS, out.length, "the canonical width is the storage contract");
        assertEquals(1f, out[0], 1e-6f);
        for (int i = 1; i < out.length; i++) {
            assertEquals(0f, out[i], 1e-6f, "expansion pads with zeros at index " + i);
        }
    }

    @Test
    void theResponseCarriesTheCallsAccountingNextToTheVector() throws IOException, InterruptedException {
        FixedVectorClient client = new FixedVectorClient(new float[]{3f, 4f, 0f, 0f});
        client.setModel(spec(4));
        EmbeddingsResponse response = client.embed("t", EmbeddingPurpose.QUERY);
        assertArrayEquals(new float[]{0.6f, 0.8f, 0f, 0f}, response.getVector(), 1e-6f);
        assertEquals("dim-test-4", response.getModel());
        assertEquals("fake-embeddings", response.getProvider());
        assertEquals(7, response.getActualInputTokens(), "what the provider billed");
        assertEquals(EmbeddingPurpose.QUERY, response.getPurpose());
        assertTrue(response.isSuccessful());
        assertNotNull(response.getEndTime());
        assertNull(response.getContext(), "an embeddings call is no conversation");
        assertNull(response.getResponseMessage());
        assertEquals(0, response.getLastOutputTokens());
    }
}
