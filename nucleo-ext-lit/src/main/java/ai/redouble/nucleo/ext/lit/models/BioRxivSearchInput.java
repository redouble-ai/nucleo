/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit.models;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input parameters for searching bioRxiv and medRxiv preprints.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-12)
 */
@LLMDescription("Parameters for searching preprint articles in bioRxiv and medRxiv databases")
public class BioRxivSearchInput  {

    @LLMRequired
    @LLMDescription("Search query keywords. Filtered in-memory against titles and abstracts returned by the date-range API call.")
    @LLMExample("CRISPR gene editing cancer")
    private String query;

    @LLMRequired
    @LLMDescription("Start date for the API window to search (YYYY-MM-DD). Keep the window narrow: the API returns all preprints in the window before keyword filtering.")
    @LLMExample("2024-06-01")
    private String dateFrom;

    @LLMRequired
    @LLMDescription("End date for the API window to search (YYYY-MM-DD, inclusive).")
    @LLMExample("2024-12-31")
    private String dateTo;

    @LLMDescription("Filter by subject category (e.g., 'cell biology', 'neuroscience', 'bioinformatics')")
    @LLMExample("cell biology")
    private String category;

    @LLMDescription("Maximum number of results to return (1-100, default 20)")
    @LLMExample("20")
    private Integer maxResults;

    @LLMDescription("Which server to search: 'biorxiv', 'medrxiv', or 'both' (default 'both')")
    @LLMExample("biorxiv")
    private String server;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(Integer maxResults) {
        this.maxResults = maxResults;
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }
}
