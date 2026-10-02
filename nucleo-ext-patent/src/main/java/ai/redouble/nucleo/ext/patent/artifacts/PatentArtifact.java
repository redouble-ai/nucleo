/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.artifacts;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Artifact representing a patent document with bibliographic front-page data.
 * Immutable once created and registered in the artifact registry.
 *
 * <p>Additional data (claims, description, family, legal events, citations, images)
 * lives on separate tool output containers, not on this artifact. The LLM holds
 * both in context and joins them via {@code patentNumber}.
 *
 * <p>For full-text content (claims + description), see {@link PatentFullContentArtifact}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
@TypeAlias("link:patent")
public class PatentArtifact extends LinkArtifact {
    @LLMDescription("Publication number as its source takes it back: dotted DOCDB from EPO (e.g., EP.1000000.B1), the USPTO number from USPTO (e.g., US7629360B2)")
    private String patentNumber;
    @LLMDescription("Application number, dotted DOCDB from EPO (e.g., EP.00123456)")
    private String applicationNumber;
    @LLMSummarizable(value = "patent abstract", threshold = 500)
    @LLMDescription("Patent abstract text")
    private String patentAbstract;
    @LLMDescription("Assignees/applicants")
    private List<String> applicants;
    @LLMDescription("Named inventors")
    private List<String> inventors;
    @LLMDescription("Filing date (YYYY-MM-DD)")
    private String filingDate;
    @LLMDescription("Publication date (YYYY-MM-DD)")
    private String publicationDate;
    @LLMDescription("Priority date (YYYY-MM-DD)")
    private String priorityDate;
    @LLMDescription("IPC classification codes")
    private List<String> ipcClassifications;
    @LLMDescription("CPC classification codes")
    private List<String> cpcClassifications;
    @LLMDescription("Patent authority (EP, US, WO, JP, CN, etc.)")
    private String jurisdiction;
    @LLMDescription("Document kind code (A1, B1, B2, etc.)")
    private String kindCode;
    @LLMDescription("Data source (epo, uspto-odp)")
    private String source;
    @LLMDescription("Brief description of why this patent is relevant")
    private String relevance;
    public PatentArtifact() {
    }
    public String getPatentNumber() {
        return patentNumber;
    }
    public void setPatentNumber(String patentNumber) {
        this.patentNumber = patentNumber;
    }
    public String getApplicationNumber() {
        return applicationNumber;
    }
    public void setApplicationNumber(String applicationNumber) {
        this.applicationNumber = applicationNumber;
    }
    public String getPatentAbstract() {
        return patentAbstract;
    }
    public void setPatentAbstract(String patentAbstract) {
        this.patentAbstract = patentAbstract;
    }
    public List<String> getApplicants() {
        return applicants;
    }
    public void setApplicants(List<String> applicants) {
        this.applicants = applicants;
    }
    public List<String> getInventors() {
        return inventors;
    }
    public void setInventors(List<String> inventors) {
        this.inventors = inventors;
    }
    public String getFilingDate() {
        return filingDate;
    }
    public void setFilingDate(String filingDate) {
        this.filingDate = filingDate;
    }
    public String getPublicationDate() {
        return publicationDate;
    }
    public void setPublicationDate(String publicationDate) {
        this.publicationDate = publicationDate;
    }
    public String getPriorityDate() {
        return priorityDate;
    }
    public void setPriorityDate(String priorityDate) {
        this.priorityDate = priorityDate;
    }
    public List<String> getIpcClassifications() {
        return ipcClassifications;
    }
    public void setIpcClassifications(List<String> ipcClassifications) {
        this.ipcClassifications = ipcClassifications;
    }
    public List<String> getCpcClassifications() {
        return cpcClassifications;
    }
    public void setCpcClassifications(List<String> cpcClassifications) {
        this.cpcClassifications = cpcClassifications;
    }
    public String getJurisdiction() {
        return jurisdiction;
    }
    public void setJurisdiction(String jurisdiction) {
        this.jurisdiction = jurisdiction;
    }
    public String getKindCode() {
        return kindCode;
    }
    public void setKindCode(String kindCode) {
        this.kindCode = kindCode;
    }
    public String getSource() {
        return source;
    }
    public void setSource(String source) {
        this.source = source;
    }
    public String getRelevance() {
        return relevance;
    }
    public void setRelevance(String relevance) {
        this.relevance = relevance;
    }
    public String getFormattedPatent() {
        StringBuilder sb = new StringBuilder();
        String title = getTitle();
        if (title != null) {
            sb.append(title);
        }
        if (patentNumber != null) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("(").append(patentNumber).append(")");
        }
        if (jurisdiction != null) {
            sb.append(" - ").append(jurisdiction);
        }
        return sb.toString();
    }
}
