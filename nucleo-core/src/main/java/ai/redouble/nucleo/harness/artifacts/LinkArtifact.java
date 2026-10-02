/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Base artifact for any entity that has a title and a URL.
 *
 * <p>Captures the common concept of a "clickable link" shared by web pages,
 * citations, patents, clinical trials, and search results. Enables hierarchical
 * type resolution: if you don't know {@code link:cite:pubmed}, you fall back to
 * {@code link:cite}, then {@code link} - and at every level you get something
 * useful (at minimum: a clickable link).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-21)
 */
@TypeAlias("link")
public class LinkArtifact extends AbstractArtifact {
    @LLMDescription("Title of the linked resource")
    private String title;
    @LLMDescription("URL of the linked resource")
    private String url;
    public LinkArtifact() {
    }
    public String getTitle() {
        return title;
    }
    public void setTitle(String title) {
        this.title = title;
    }
    public String getUrl() {
        return url;
    }
    public void setUrl(String url) {
        this.url = url;
    }
}
