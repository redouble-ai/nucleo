/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import ai.redouble.nucleo.harness.llm.*;

/**
 * A text to turn into a vector, keyed by what it belongs to: a file's path for the index,
 * or a query for a search.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class TextToEmbed {
    private String key;
    private String text;
    private EmbeddingPurpose purpose;

    public String getKey() {return key;}

    public void setKey(String key) {this.key = key;}

    public String getText() {return text;}

    public void setText(String text) {this.text = text;}

    public EmbeddingPurpose getPurpose() {return purpose;}

    public void setPurpose(EmbeddingPurpose purpose) {this.purpose = purpose;}
}
