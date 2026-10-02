/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Artifact representing a web page from search results.
 *
 * <p>This is a lightweight artifact for web pages discovered through search engines
 * like Brave or Exa. Unlike CitationArtifact which is designed for scholarly works,
 * this class handles general web content.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
@TypeAlias("link:page")
public class WebPageArtifact extends LinkArtifact {
    @LLMDescription("Description or snippet from the page")
    private String description;
    @LLMDescription("Source search engine or discovery method (e.g., 'brave', 'exa', 'browser')")
    private String source;
    @LLMDescription("Full text content of the page (if fetched)")
    @LLMSummarizable(value = "web page content", size = SummarySize.BRIEF)
    private String content;
    @LLMDescription("Brief explanation of why this page is relevant")
    private String relevance;
    public WebPageArtifact() {
    }
    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }
    public String getSource() {
        return source;
    }
    public void setSource(String source) {
        this.source = source;
    }
    public String getContent() {
        return content;
    }
    public void setContent(String content) {
        this.content = content;
    }
    public String getRelevance() {
        return relevance;
    }
    public void setRelevance(String relevance) {
        this.relevance = relevance;
    }
}
