/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input for the SummarizationTool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
@LLMDescription("Parameters for text summarization")
public class SummarizationInput  {
    @LLMRequired
    @LLMDescription("The text to summarize")
    private String text;

    @LLMRequired
    @LLMDescription("Target summary size: BRIEF (one or two sentences), SHORT (about a paragraph), PARAGRAPHS (a couple of short paragraphs)")
    private SummarySize size;

    @LLMDescription("Optional context about the text type (e.g., 'scientific abstract', 'news article')")
    private String context;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public SummarySize getSize() {
        return size;
    }

    public void setSize(SummarySize size) {
        this.size = size;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }
}
