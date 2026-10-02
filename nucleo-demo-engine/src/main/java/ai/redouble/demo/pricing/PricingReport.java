/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.pricing;

import ai.redouble.demo.extract.*;
import java.util.*;

/**
 * What the pricing run found: every product with its current price per audience and
 * currency and the history behind it, the costs the documents state, the mentions the
 * arithmetic could not use, one row per document read, and what the run spent.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public class PricingReport {
    public enum DocumentStatus {
        /** The model read it and answered. */
        READ,
        /** Refused at admission by the spend cap. */
        REFUSED,
        /** The call failed; the note says why. */
        FAILED
    }

    /** One document's row: what reading it produced and cost. */
    public static class DocumentOutcome {
        private String path;
        private DocumentStatus status;
        private int mentions;
        private String documentDate;
        private String modelId;
        private String servedModelId;
        private Long inputTokens;
        private Long outputTokens;
        private Long latencyMs;
        private Double cost;
        private String currency;
        private String note;

        public String getPath() {return path;}

        public void setPath(String path) {this.path = path;}

        public DocumentStatus getStatus() {return status;}

        public void setStatus(DocumentStatus status) {this.status = status;}

        public int getMentions() {return mentions;}

        public void setMentions(int mentions) {this.mentions = mentions;}

        public String getDocumentDate() {return documentDate;}

        public void setDocumentDate(String documentDate) {this.documentDate = documentDate;}

        public String getModelId() {return modelId;}

        public void setModelId(String modelId) {this.modelId = modelId;}

        public String getServedModelId() {return servedModelId;}

        public void setServedModelId(String servedModelId) {this.servedModelId = servedModelId;}

        public Long getInputTokens() {return inputTokens;}

        public void setInputTokens(Long inputTokens) {this.inputTokens = inputTokens;}

        public Long getOutputTokens() {return outputTokens;}

        public void setOutputTokens(Long outputTokens) {this.outputTokens = outputTokens;}

        public Long getLatencyMs() {return latencyMs;}

        public void setLatencyMs(Long latencyMs) {this.latencyMs = latencyMs;}

        public Double getCost() {return cost;}

        public void setCost(Double cost) {this.cost = cost;}

        public String getCurrency() {return currency;}

        public void setCurrency(String currency) {this.currency = currency;}

        public String getNote() {return note;}

        public void setNote(String note) {this.note = note;}
    }

    private String workflowId;
    private String generatedAt;
    /** The day the run answered for: a price dated after it is scheduled, not current. */
    private String asOf;
    private String outputFile;
    private long elapsedMs;
    private int documentsRead;
    private int mentionsFound;
    private String canonicalizationNote;
    private List<ProductPricing> products = new ArrayList<>();
    private List<PricePoint> ignored = new ArrayList<>();
    private List<DocumentOutcome> documents = new ArrayList<>();
    private RunSpend spend;

    public String getWorkflowId() {return workflowId;}

    public void setWorkflowId(String workflowId) {this.workflowId = workflowId;}

    public String getGeneratedAt() {return generatedAt;}

    public void setGeneratedAt(String generatedAt) {this.generatedAt = generatedAt;}

    public String getAsOf() {return asOf;}

    public void setAsOf(String asOf) {this.asOf = asOf;}

    public String getOutputFile() {return outputFile;}

    public void setOutputFile(String outputFile) {this.outputFile = outputFile;}

    public long getElapsedMs() {return elapsedMs;}

    public void setElapsedMs(long elapsedMs) {this.elapsedMs = elapsedMs;}

    public int getDocumentsRead() {return documentsRead;}

    public void setDocumentsRead(int documentsRead) {this.documentsRead = documentsRead;}

    public int getMentionsFound() {return mentionsFound;}

    public void setMentionsFound(int mentionsFound) {this.mentionsFound = mentionsFound;}

    public String getCanonicalizationNote() {return canonicalizationNote;}

    public void setCanonicalizationNote(String canonicalizationNote) {this.canonicalizationNote = canonicalizationNote;}

    public List<ProductPricing> getProducts() {return products;}

    public void setProducts(List<ProductPricing> products) {this.products = products;}

    public List<PricePoint> getIgnored() {return ignored;}

    public void setIgnored(List<PricePoint> ignored) {this.ignored = ignored;}

    public List<DocumentOutcome> getDocuments() {return documents;}

    public void setDocuments(List<DocumentOutcome> documents) {this.documents = documents;}

    public RunSpend getSpend() {return spend;}

    public void setSpend(RunSpend spend) {this.spend = spend;}
}
