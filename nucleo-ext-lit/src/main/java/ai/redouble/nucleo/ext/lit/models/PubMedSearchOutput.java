/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit.models;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Output from PubMed article search.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-03)
 */
@LLMDescription("Results from PubMed literature search")
public class PubMedSearchOutput  {

    @LLMRequired
    @LLMDescription("List of articles found, ordered by relevance or date")
    private List<PubMedArticle> articles;

    @LLMRequired
    @LLMDescription("Total number of articles matching the query (may exceed returned results)")
    private Integer totalCount;

    @LLMDescription("The search query that was executed")
    private String queryExecuted;

    @LLMDescription("Whether results were truncated due to maxResults limit")
    private Boolean truncated;

    public List<PubMedArticle> getArticles() {
        return articles;
    }

    public void setArticles(List<PubMedArticle> articles) {
        this.articles = articles;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public String getQueryExecuted() {
        return queryExecuted;
    }

    public void setQueryExecuted(String queryExecuted) {
        this.queryExecuted = queryExecuted;
    }

    public Boolean getTruncated() {
        return truncated;
    }

    public void setTruncated(Boolean truncated) {
        this.truncated = truncated;
    }

    /**
     * Individual PubMed article with metadata.
     * Extends CitationArtifact to enable preservation across agent workflows.
     */
    @TypeAlias("link:cite:pubmed")
    @LLMDescription("Individual PubMed article with bibliographic metadata")
    public static class PubMedArticle extends CitationArtifact {

        @LLMRequired
        @LLMDescription("PubMed ID (PMID) - unique identifier")
        private String pmid;

        @LLMRequired
        @LLMDescription("Publication date (YYYY/MM/DD format)")
        private String publicationDate;

        @LLMDescription("PubMed Central ID (PMCID) if available")
        private String pmcid;

        @LLMDescription("Publication types (e.g., Journal Article, Review, Clinical Trial)")
        private List<String> publicationTypes;

        @LLMDescription("Medical Subject Headings (MeSH) terms")
        private List<String> meshTerms;

        public PubMedArticle() {
            super();
            // Set source to PubMed by default
            setSource("pubmed");
        }

        public String getPmid() {
            return pmid;
        }

        public void setPmid(String pmid) {
            this.pmid = pmid;
            // Also set URL based on PMID
            if (pmid != null) {
                setUrl("https://pubmed.ncbi.nlm.nih.gov/" + pmid + "/");
            }
        }

        public String getPublicationDate() {
            return publicationDate;
        }

        public void setPublicationDate(String publicationDate) {
            this.publicationDate = publicationDate;
            // Extract year from date for CitationArtifact's year field
            if (publicationDate != null && publicationDate.length() >= 4) {
                setYear(publicationDate.substring(0, 4));
            }
        }

        public String getPmcid() {
            return pmcid;
        }

        public void setPmcid(String pmcid) {
            this.pmcid = pmcid;
        }

        public List<String> getPublicationTypes() {
            return publicationTypes;
        }

        public void setPublicationTypes(List<String> publicationTypes) {
            this.publicationTypes = publicationTypes;
        }

        public List<String> getMeshTerms() {
            return meshTerms;
        }

        public void setMeshTerms(List<String> meshTerms) {
            this.meshTerms = meshTerms;
        }
    }
}