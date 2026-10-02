/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import java.util.*;

/**
 * What a run did: one row per file with the tier that handled it, the model that served it
 * and what that cost, and the run's totals per currency and per model from the ledger.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class ExtractReport {
    private String workflowId;
    private String directory;
    private int filesSeen;
    private long elapsedMs;
    private Map<Tier, Integer> byTier = new EnumMap<>(Tier.class);
    private List<FileOutcome> files = new ArrayList<>();
    private Map<String, Double> spent = new LinkedHashMap<>();
    private Map<String, Double> caps = new LinkedHashMap<>();
    private List<ModelUsage> byModel = new ArrayList<>();
    private long llmCalls;
    private long unpricedCalls;
    private int indexed;
    private String embeddingsModel;

    /** One file's row. */
    public static class FileOutcome {
        private String path;
        private Tier tier;
        private int chars;
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

        public Tier getTier() {return tier;}

        public void setTier(Tier tier) {this.tier = tier;}

        public int getChars() {return chars;}

        public void setChars(int chars) {this.chars = chars;}

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

    /** One model's share of the run. */
    public static class ModelUsage {
        private String modelId;
        private long calls;
        private long inputTokens;
        private long outputTokens;
        private long latencyMs;
        private Double cost;
        private String currency;

        public String getModelId() {return modelId;}

        public void setModelId(String modelId) {this.modelId = modelId;}

        public long getCalls() {return calls;}

        public void setCalls(long calls) {this.calls = calls;}

        public long getInputTokens() {return inputTokens;}

        public void setInputTokens(long inputTokens) {this.inputTokens = inputTokens;}

        public long getOutputTokens() {return outputTokens;}

        public void setOutputTokens(long outputTokens) {this.outputTokens = outputTokens;}

        public long getLatencyMs() {return latencyMs;}

        public void setLatencyMs(long latencyMs) {this.latencyMs = latencyMs;}

        public Double getCost() {return cost;}

        public void setCost(Double cost) {this.cost = cost;}

        public String getCurrency() {return currency;}

        public void setCurrency(String currency) {this.currency = currency;}
    }

    public String getWorkflowId() {return workflowId;}

    public void setWorkflowId(String workflowId) {this.workflowId = workflowId;}

    public String getDirectory() {return directory;}

    public void setDirectory(String directory) {this.directory = directory;}

    public int getFilesSeen() {return filesSeen;}

    public void setFilesSeen(int filesSeen) {this.filesSeen = filesSeen;}

    public long getElapsedMs() {return elapsedMs;}

    public void setElapsedMs(long elapsedMs) {this.elapsedMs = elapsedMs;}

    public Map<Tier, Integer> getByTier() {return byTier;}

    public void setByTier(Map<Tier, Integer> byTier) {this.byTier = byTier;}

    public List<FileOutcome> getFiles() {return files;}

    public void setFiles(List<FileOutcome> files) {this.files = files;}

    public Map<String, Double> getSpent() {return spent;}

    public void setSpent(Map<String, Double> spent) {this.spent = spent;}

    public Map<String, Double> getCaps() {return caps;}

    public void setCaps(Map<String, Double> caps) {this.caps = caps;}

    public List<ModelUsage> getByModel() {return byModel;}

    public void setByModel(List<ModelUsage> byModel) {this.byModel = byModel;}

    public long getLlmCalls() {return llmCalls;}

    public void setLlmCalls(long llmCalls) {this.llmCalls = llmCalls;}

    public long getUnpricedCalls() {return unpricedCalls;}

    public void setUnpricedCalls(long unpricedCalls) {this.unpricedCalls = unpricedCalls;}

    public int getIndexed() {return indexed;}

    public void setIndexed(int indexed) {this.indexed = indexed;}

    public String getEmbeddingsModel() {return embeddingsModel;}

    public void setEmbeddingsModel(String embeddingsModel) {this.embeddingsModel = embeddingsModel;}
}
