/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

/**
 * A vector and the key it embeds, plus the model that produced it: vectors are comparable
 * only with vectors from the same model, so the index records which one every vector came from.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class Embedding {
    private String key;
    private float[] vector;
    private String modelId;

    public String getKey() {return key;}

    public void setKey(String key) {this.key = key;}

    public float[] getVector() {return vector;}

    public void setVector(float[] vector) {this.vector = vector;}

    public String getModelId() {return modelId;}

    public void setModelId(String modelId) {this.modelId = modelId;}
}
