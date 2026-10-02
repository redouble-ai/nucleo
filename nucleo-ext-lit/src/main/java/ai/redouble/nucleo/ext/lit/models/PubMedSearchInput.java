/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit.models;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input parameters for searching PubMed articles.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-03)
 */
@LLMDescription("Parameters for searching biomedical literature in PubMed database")
public class PubMedSearchInput  {

    @LLMRequired
    @LLMDescription("Search query using PubMed syntax (e.g., 'breast cancer AND immunotherapy', 'diabetes[MeSH Terms]')")
    @LLMExample("CRISPR gene editing")
    private String query;

    @LLMDescription("Maximum number of results to return (1-100, default 20)")
    @LLMExample("20")
    private Integer maxResults;

    @LLMDescription("Sort order: 'relevance' (default), 'pub_date' (newest first), or 'pub_date_asc' (oldest first)")
    @LLMExample("pub_date")
    private String sortBy;

    @LLMDescription("Start date for filtering results (YYYY/MM/DD or YYYY/MM or YYYY)")
    @LLMExample("2020/01/01")
    private String dateFrom;

    @LLMDescription("End date for filtering results (YYYY/MM/DD or YYYY/MM or YYYY)")
    @LLMExample("2024/12/31")
    private String dateTo;

    @LLMDescription("Filter by publication type (e.g., 'Review', 'Clinical Trial', 'Meta-Analysis')")
    @LLMExample("Review")
    private String publicationType;

    @LLMDescription("Whether to include full abstracts in results (default false for performance)")
    private Boolean includeAbstracts;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Integer getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(Integer maxResults) {
        this.maxResults = maxResults;
    }

    public String getSortBy() {
        return sortBy;
    }

    public void setSortBy(String sortBy) {
        this.sortBy = sortBy;
    }

    public String getDateFrom() {
        return dateFrom;
    }

    public void setDateFrom(String dateFrom) {
        this.dateFrom = dateFrom;
    }

    public String getDateTo() {
        return dateTo;
    }

    public void setDateTo(String dateTo) {
        this.dateTo = dateTo;
    }

    public String getPublicationType() {
        return publicationType;
    }

    public void setPublicationType(String publicationType) {
        this.publicationType = publicationType;
    }

    public Boolean getIncludeAbstracts() {
        return includeAbstracts;
    }

    public void setIncludeAbstracts(Boolean includeAbstracts) {
        this.includeAbstracts = includeAbstracts;
    }
}