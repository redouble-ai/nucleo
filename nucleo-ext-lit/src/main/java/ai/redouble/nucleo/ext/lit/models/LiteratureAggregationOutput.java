/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit.models;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.thinking.*;

import java.util.*;

/**
 * Output from literature aggregation analysis.
 * Contains a synthesis of findings from multiple papers plus
 * references to the most relevant individual articles.
 * Supports both published papers (PubMed) and preprints (bioRxiv/medRxiv).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-03)
 */
@LLMDescription("Results from aggregating and synthesizing biomedical literature (published papers and preprints) on a research topic. When using these results always provide properly formatted references (as is customary in scientific literature) with markdown-formatted external links to the articles")
public class LiteratureAggregationOutput extends ThinkerOutput<ChainOfThoughtReasoning> {

    @LLMRequired
    @LLMDescription("Comprehensive summary synthesizing key findings, themes, and insights from the literature using STRICT Markdown only: H3-H6 headers (###-######), lists with * at column 0 (no leading spaces), **bold**, *italic*")
    @LLMSummarizable(value = "literature synthesis summary", threshold = 4000, llmSafe = false)
    private String summary;

    @LLMRequired
    @LLMDescription("List of the most relevant articles with brief descriptions")
    private List<CitationArtifact> relevantArticles;

    @LLMDescription("Number of articles analyzed to produce this aggregation")
    private Integer articlesAnalyzed;

    @LLMDescription("Key themes or topics identified across the literature")
    private List<String> keyThemes;

    @LLMDescription("Important gaps or limitations identified in the current research")
    @LLMSummarizable(value = "research gaps and limitations", threshold = 2000)
    private String researchGaps;

    public LiteratureAggregationOutput() {
        super();  // Calls ArtifactResponse constructor
        setReasoning(new ChainOfThoughtReasoning());
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<CitationArtifact> getRelevantArticles() {
        return relevantArticles;
    }

    public void setRelevantArticles(List<CitationArtifact> relevantArticles) {
        this.relevantArticles = relevantArticles;
    }

    public Integer getArticlesAnalyzed() {
        return articlesAnalyzed;
    }

    public void setArticlesAnalyzed(Integer articlesAnalyzed) {
        this.articlesAnalyzed = articlesAnalyzed;
    }

    public List<String> getKeyThemes() {
        return keyThemes;
    }

    public void setKeyThemes(List<String> keyThemes) {
        this.keyThemes = keyThemes;
    }

    public String getResearchGaps() {
        return researchGaps;
    }

    public void setResearchGaps(String researchGaps) {
        this.researchGaps = researchGaps;
    }

}

