/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;


/**
 * Coerces a provider's native embedding vector to the framework's canonical
 * dimension and unit length. This is the seam that lets the database vector
 * columns stay a single fixed width regardless of which provider produced the
 * vector: every client returns a canonical-dimension, L2-normalized vector, so
 * switching providers never requires a schema change.
 *
 * <p>Folding (native larger than canonical) truncates to the leading components
 * then renormalizes. This is correct for Matryoshka-trained models, where the
 * leading prefix is itself a valid lower-dimensional embedding - which covers
 * every provider the framework uses. Expanding (native smaller than canonical)
 * zero-pads; the added components carry no signal and only make a smaller-native
 * vector fit the fixed column.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-14)
 */
public final class DimensionAdapter {

    private DimensionAdapter() {}

    /**
     * Returns a new vector of exactly {@code canonicalDim} components, L2-normalized.
     */
    public static float[] coerce(float[] raw, int canonicalDim) {
        if (raw.length == canonicalDim) {
            return normalize(raw);
        }
        if (raw.length > canonicalDim) {
            float[] folded = new float[canonicalDim];
            System.arraycopy(raw, 0, folded, 0, canonicalDim);
            return normalize(folded);
        }
        float[] expanded = new float[canonicalDim];
        System.arraycopy(raw, 0, expanded, 0, raw.length);
        return normalize(expanded);
    }

    /**
     * Returns a new unit-length copy of {@code v}. The zero vector has no
     * direction, so it is returned unchanged rather than divided into NaN.
     */
    public static float[] normalize(float[] v) {
        double sumSq = 0.0;
        for (float x : v) {
            sumSq += (double) x * x;
        }
        double norm = Math.sqrt(sumSq);
        if (norm < 1e-12) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }
}
