/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit.models;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Output from fetching a full-text article from PubMed Central.
 * Contains a PMCArticle artifact if the article was found.
 * Null article means the article is not available in PMC.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-25)
 */
@LLMDescription("Result of fetching a full-text article from PubMed Central. Article is null if not available in PMC.")
public class PMCFullTextOutput  {
    @LLMDescription("Article artifact with citation metadata and full text content. Null if article not found in PMC.")
    private PMCArticle article;

    public PMCArticle getArticle() {
        return article;
    }

    public void setArticle(PMCArticle article) {
        this.article = article;
    }
}
