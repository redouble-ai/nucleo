/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit.models;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;

/**
 * Artifact representing a full-text article from PubMed Central.
 * <p>
 * Extends CitationArtifact with the PMCID identifier and full text content.
 * The full text is preserved in the artifact registry and summarized for the LLM;
 * parent doers access the actual content via artifact propagation.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-25)
 */
@TypeAlias("link:cite:pmc")
public class PMCArticle extends CitationArtifact {
    @LLMDescription("PubMed Central ID (e.g. PMC1234567)")
    private String pmcid;
    @LLMDescription("PubMed ID if available")
    private String pmid;
    @LLMSummarizable(value = "full text of a biomedical research article from PubMed Central", threshold = 1000, size = SummarySize.PARAGRAPHS)
    @LLMDescription("Full text content of the article. Use get_artifact_field to access the complete text.")
    private String fullText;

    public PMCArticle() {
    }

    public String getPmcid() {
        return pmcid;
    }

    public void setPmcid(String pmcid) {
        this.pmcid = pmcid;
        if (pmcid != null && getUrl() == null) {
            setUrl("https://www.ncbi.nlm.nih.gov/pmc/articles/" + pmcid + "/");
        }
    }

    public String getPmid() {
        return pmid;
    }

    public void setPmid(String pmid) {
        this.pmid = pmid;
    }

    public String getFullText() {
        return fullText;
    }

    public void setFullText(String fullText) {
        this.fullText = fullText;
    }
}
