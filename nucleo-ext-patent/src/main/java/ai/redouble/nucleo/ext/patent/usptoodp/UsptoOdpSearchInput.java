/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.usptoodp;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input for USPTO Open Data Portal patent search.
 * At least one search criterion must be provided.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class UsptoOdpSearchInput {
    @LLMDescription("Search term for invention title")
    private String titleSearch;
    @LLMDescription("Assignee/applicant organization name")
    private String assignee;
    @LLMDescription("Inventor last name")
    private String inventorLastName;
    @LLMDescription("CPC classification code (e.g., A61K31)")
    private String cpcClassification;
    @LLMDescription("Filing date range start (YYYY-MM-DD)")
    private String dateFrom;
    @LLMDescription("Filing date range end (YYYY-MM-DD)")
    private String dateTo;
    @LLMDescription("Maximum results to return (default 25, max 100)")
    private Integer maxResults;
    @LLMDescription("Include pending applications in addition to granted patents")
    private Boolean includeApplications;
    public UsptoOdpSearchInput() {
    }
    public String getTitleSearch() {
        return titleSearch;
    }
    public void setTitleSearch(String titleSearch) {
        this.titleSearch = titleSearch;
    }
    public String getAssignee() {
        return assignee;
    }
    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }
    public String getInventorLastName() {
        return inventorLastName;
    }
    public void setInventorLastName(String inventorLastName) {
        this.inventorLastName = inventorLastName;
    }
    public String getCpcClassification() {
        return cpcClassification;
    }
    public void setCpcClassification(String cpcClassification) {
        this.cpcClassification = cpcClassification;
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
    public Integer getMaxResults() {
        return maxResults;
    }
    public void setMaxResults(Integer maxResults) {
        this.maxResults = maxResults;
    }
    public Boolean getIncludeApplications() {
        return includeApplications;
    }
    public void setIncludeApplications(Boolean includeApplications) {
        this.includeApplications = includeApplications;
    }
}
