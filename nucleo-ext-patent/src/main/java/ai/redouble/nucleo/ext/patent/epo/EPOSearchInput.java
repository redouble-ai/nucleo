/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input for EPO patent search.
 * Supports raw CQL queries or structured field-based searching.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
public class EPOSearchInput  {
    @LLMDescription("Raw CQL query string (if provided, overrides structured fields)")
    private String cqlQuery;
    @LLMDescription("Search in patent title")
    private String title;
    @LLMDescription("Search in title and abstract combined")
    private String titleAbstract;
    @LLMDescription("Applicant/assignee name")
    private String applicant;
    @LLMDescription("Inventor name")
    private String inventor;
    @LLMDescription("Specific patent number to search")
    private String patentNumber;
    @LLMDescription("IPC classification code (e.g., A61K31/00)")
    private String ipcClass;
    @LLMDescription("CPC classification code")
    private String cpcClass;
    @LLMDescription("Date range start in YYYY-MM-DD format")
    private String dateFrom;
    @LLMDescription("Date range end in YYYY-MM-DD format")
    private String dateTo;
    @LLMDescription("Maximum number of results to return (default: 25, max: 100)")
    private Integer maxResults;
    public EPOSearchInput() {
    }
    public String getCqlQuery() {
        return cqlQuery;
    }
    public void setCqlQuery(String cqlQuery) {
        this.cqlQuery = cqlQuery;
    }
    public String getTitle() {
        return title;
    }
    public void setTitle(String title) {
        this.title = title;
    }
    public String getTitleAbstract() {
        return titleAbstract;
    }
    public void setTitleAbstract(String titleAbstract) {
        this.titleAbstract = titleAbstract;
    }
    public String getApplicant() {
        return applicant;
    }
    public void setApplicant(String applicant) {
        this.applicant = applicant;
    }
    public String getInventor() {
        return inventor;
    }
    public void setInventor(String inventor) {
        this.inventor = inventor;
    }
    public String getPatentNumber() {
        return patentNumber;
    }
    public void setPatentNumber(String patentNumber) {
        this.patentNumber = patentNumber;
    }
    public String getIpcClass() {
        return ipcClass;
    }
    public void setIpcClass(String ipcClass) {
        this.ipcClass = ipcClass;
    }
    public String getCpcClass() {
        return cpcClass;
    }
    public void setCpcClass(String cpcClass) {
        this.cpcClass = cpcClass;
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
}
