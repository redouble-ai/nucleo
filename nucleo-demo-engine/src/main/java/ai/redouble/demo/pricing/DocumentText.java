/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.pricing;

import ai.redouble.nucleo.harness.schema.*;

/**
 * What the price extractor sees: one document's path, for the model to reason about the
 * kind of document it is, and the text the extractor produced for it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public class DocumentText {
    @LLMDescription("The file's path relative to the directory; the name says what kind of document it is, and a date in it is the document's date when the text carries none")
    private String path;
    @LLMDescription("The document's text as extracted: prose, a table as rows, a transcription of a scan or photo")
    private String text;

    public String getPath() {return path;}

    public void setPath(String path) {this.path = path;}

    public String getText() {return text;}

    public void setText(String text) {this.text = text;}
}
