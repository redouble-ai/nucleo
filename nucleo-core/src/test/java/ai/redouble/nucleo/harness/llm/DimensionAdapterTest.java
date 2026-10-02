/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DimensionAdapter}: the fold/expand/normalize coercion
 * that lets the database vector columns stay a single fixed width regardless of
 * which provider produced the embedding.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-14)
 */
public class DimensionAdapterTest {

    private static final float TOL = 1e-5f;

    private static double norm(float[] v) {
        double s = 0.0;
        for (float x : v) {
            s += (double) x * x;
        }
        return Math.sqrt(s);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot / (norm(a) * norm(b));
    }

    @Test
    void identityNormalizesToUnitLengthSameDirection() {
        float[] raw = {3f, 0f, 4f};  // length 5
        float[] out = DimensionAdapter.coerce(raw, 3);
        assertEquals(3, out.length);
        assertEquals(1.0, norm(out), TOL);
        // No rotation: cosine with the original is 1, so cosine-distance ranking is preserved.
        assertEquals(1.0, cosine(out, raw), TOL);
    }

    @Test
    void normalizeProducesUnitVector() {
        float[] out = DimensionAdapter.normalize(new float[] {0f, 0f, 2f});
        assertEquals(1.0, norm(out), TOL);
        assertEquals(1.0f, out[2], TOL);
    }

    @Test
    void foldTruncatesToCanonicalAndRenormalizes() {
        float[] raw = {1f, 2f, 2f, 10f};  // canonical keeps the leading 3
        float[] out = DimensionAdapter.coerce(raw, 3);
        assertEquals(3, out.length);
        assertEquals(1.0, norm(out), TOL);
        // Direction matches the normalized leading-3 prefix, not the full vector.
        float[] prefix = {1f, 2f, 2f};
        assertEquals(1.0, cosine(out, prefix), TOL);
    }

    @Test
    void expandZeroPadsToCanonicalAndStaysUnit() {
        float[] raw = {3f, 4f};  // native smaller than canonical
        float[] out = DimensionAdapter.coerce(raw, 4);
        assertEquals(4, out.length);
        assertEquals(1.0, norm(out), TOL);
        assertEquals(0.6f, out[0], TOL);
        assertEquals(0.8f, out[1], TOL);
        assertEquals(0f, out[2], TOL);
        assertEquals(0f, out[3], TOL);
    }

    @Test
    void zeroVectorReturnedUnchangedNoNaN() {
        float[] raw = {0f, 0f, 0f};
        float[] out = DimensionAdapter.normalize(raw);
        for (float x : out) {
            assertEquals(0f, x, 0f);
            assertTrue(!Float.isNaN(x));
        }
    }

    @Test
    void coerceDoesNotMutateInput() {
        float[] raw = {3f, 0f, 4f};
        DimensionAdapter.coerce(raw, 3);
        assertEquals(3f, raw[0], 0f);
        assertEquals(0f, raw[1], 0f);
        assertEquals(4f, raw[2], 0f);
    }
}
