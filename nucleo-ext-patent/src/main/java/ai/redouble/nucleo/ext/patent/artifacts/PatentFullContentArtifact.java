/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.artifacts;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Extended patent artifact that includes the full claims and description text.
 * Returned only by explicit full-text tools ({@code EPOFullTextTool},
 * {@code UsptoOdpFullTextTool}) when the thinker requests the patent body.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
@TypeAlias("link:patent:fulltext")
public class PatentFullContentArtifact extends PatentArtifact {
    @LLMSummarizable(value = "patent claims", threshold = 2000, size = SummarySize.PARAGRAPHS)
    @LLMDescription("Full claims text")
    private String claims;
    @LLMSummarizable(value = "patent specification", threshold = 2000, size = SummarySize.PARAGRAPHS)
    @LLMDescription("Full description/specification text")
    private String description;
    public PatentFullContentArtifact() {
    }
    public String getClaims() {
        return claims;
    }
    public void setClaims(String claims) {
        this.claims = claims;
    }
    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }
}
