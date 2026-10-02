/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;


import java.util.*;

/**
 * Shared {@link ModelSpec} implementation: plain mutable fields populated by the catalog
 * backend (no-arg constructor + setters), {@code equals}/{@code hashCode} pinned to
 * {@link #getId() id}, and the thinking-budget scaling shared by every provider.
 *
 * <p>Provider subtypes add endpoint-specific fields (e.g. the Anthropic artifact's
 * {@code AnthropicModelSpec.getCacheBreakpoints()}).
 * The base carries everything cross-provider machinery reads.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public abstract class AbstractModelSpec implements ModelSpec {
    private String id;
    private String identity;
    private String providerKey;
    private String wireModelId;
    private int maxContextTokens;
    private int maxOutputTokens;
    private boolean supportsVision;
    private boolean supportsDocuments;
    private List<String> inputModalities;
    private List<String> outputModalities;
    private ThinkingMode thinkingMode = ThinkingMode.NONE;
    private Grade grade;
    private Integer embeddingDimensions;
    private ModelStatus status = ModelStatus.OPEN;
    private String note;
    private boolean unverified;
    private boolean requiresLax;
    private boolean toolsSuspendReasoning;
    private int tpm;
    private int rpm;
    private Integer maxConcurrent;
    private double cacheReadMultiplier = 1.0;
    private double cacheWriteMultiplier = 1.0;
    private String currency;
    private Double inputPricePerMillion;
    private Double outputPricePerMillion;
    private Double cacheReadPricePerMillion;
    private Double cacheWritePricePerMillion;
    /**
     * The entry's own per-depth thinking budgets ({@code thinking_budgets}), or null when the
     * entry rides the framework ladder. The backend validates all five depths for a
     * reasoning-effort entry (which reasons at IMMEDIATE too) and the four non-IMMEDIATE
     * depths otherwise.
     */
    private Map<Depth, Integer> thinkingBudgets;
    /**
     * The entry's own per-rung output budgets ({@code output_budgets}), or null when the entry
     * rides the framework table. The backend validates the four sized rungs; MAX is the ceiling.
     */
    private Map<OutputSize, Integer> outputBudgets;
    private Integer comfortContextTokens;
    /** Anthropic rejects an extended-thinking budget below this; the ladder floors here. */
    public static final int MIN_THINKING_BUDGET = 1024;
    /** The framework's translation of the four sized rungs, applied when an entry declares none. */
    public static final Map<OutputSize, Integer> DEFAULT_OUTPUT_BUDGETS = Map.of(
            OutputSize.VERDICT, 1024,
            OutputSize.COMPACT, 4096,
            OutputSize.STANDARD, 16384,
            OutputSize.EXTENDED, 32768);

    @Override
    public String getId() {return id;}

    public void setId(String id) {this.id = id;}

    @Override
    public String getIdentity() {return identity;}

    public void setIdentity(String identity) {this.identity = identity;}

    @Override
    public String getProviderKey() {return providerKey;}

    public void setProviderKey(String providerKey) {this.providerKey = providerKey;}

    @Override
    public String getWireModelId() {return wireModelId;}

    public void setWireModelId(String wireModelId) {this.wireModelId = wireModelId;}

    @Override
    public int getMaxContextTokens() {return maxContextTokens;}

    public void setMaxContextTokens(int maxContextTokens) {this.maxContextTokens = maxContextTokens;}

    @Override
    public int getMaxOutputTokens() {return maxOutputTokens;}

    public void setMaxOutputTokens(int maxOutputTokens) {this.maxOutputTokens = maxOutputTokens;}

    @Override
    public boolean supportsVision() {return supportsVision;}

    public void setSupportsVision(boolean supportsVision) {this.supportsVision = supportsVision;}

    @Override
    public boolean supportsDocuments() {return supportsDocuments;}

    public void setSupportsDocuments(boolean supportsDocuments) {this.supportsDocuments = supportsDocuments;}

    @Override
    public List<String> getInputModalities() {return inputModalities;}

    public void setInputModalities(List<String> inputModalities) {this.inputModalities = inputModalities;}

    @Override
    public List<String> getOutputModalities() {return outputModalities;}

    public void setOutputModalities(List<String> outputModalities) {this.outputModalities = outputModalities;}

    @Override
    public ThinkingMode getThinkingMode() {return thinkingMode;}

    public void setThinkingMode(ThinkingMode thinkingMode) {this.thinkingMode = thinkingMode;}

    @Override
    public int getThinkingBudget(Depth depth) {
        if (thinkingBudgets != null && thinkingBudgets.containsKey(depth)) {
            return thinkingBudgets.get(depth);
        }
        return switch (depth) {
            // a reasoning-effort model reasons on every call; IMMEDIATE is its lowest effort and the
            // floor is the headroom it still needs, while the Anthropic modes attach nothing there
            case IMMEDIATE -> thinkingMode == ThinkingMode.REASONING_EFFORT ? MIN_THINKING_BUDGET : 0;
            case QUICK -> Math.max(MIN_THINKING_BUDGET, maxOutputTokens / 16);
            case STANDARD -> Math.max(MIN_THINKING_BUDGET, maxOutputTokens / 8);
            case THOROUGH, ULTRA_THOROUGH -> Math.max(MIN_THINKING_BUDGET, maxOutputTokens / 4);
        };
    }

    public Map<Depth, Integer> getThinkingBudgets() {return thinkingBudgets;}

    public void setThinkingBudgets(Map<Depth, Integer> thinkingBudgets) {this.thinkingBudgets = thinkingBudgets;}

    @Override
    public int getOutputBudget(OutputSize size) {
        if (size == OutputSize.MAX) {
            return maxOutputTokens;
        }
        Map<OutputSize, Integer> table = outputBudgets != null ? outputBudgets : DEFAULT_OUTPUT_BUDGETS;
        return Math.min(table.get(size), maxOutputTokens);
    }

    public Map<OutputSize, Integer> getOutputBudgets() {return outputBudgets;}

    public void setOutputBudgets(Map<OutputSize, Integer> outputBudgets) {this.outputBudgets = outputBudgets;}

    @Override
    public Integer getComfortContextTokens() {return comfortContextTokens;}

    public void setComfortContextTokens(Integer comfortContextTokens) {this.comfortContextTokens = comfortContextTokens;}

    @Override
    public Grade getGrade() {return grade;}

    public void setGrade(Grade grade) {this.grade = grade;}

    @Override
    public Integer getEmbeddingDimensions() {return embeddingDimensions;}

    public void setEmbeddingDimensions(Integer embeddingDimensions) {this.embeddingDimensions = embeddingDimensions;}

    @Override
    public ModelStatus getStatus() {return status;}

    public void setStatus(ModelStatus status) {this.status = status;}

    @Override
    public String getNote() {return note;}

    public void setNote(String note) {this.note = note;}

    @Override
    public boolean unverified() {return unverified;}

    public void setUnverified(boolean unverified) {this.unverified = unverified;}

    @Override
    public boolean requiresLax() {return requiresLax;}

    public void setRequiresLax(boolean requiresLax) {this.requiresLax = requiresLax;}

    @Override
    public boolean toolsSuspendReasoning() {return toolsSuspendReasoning;}

    public void setToolsSuspendReasoning(boolean toolsSuspendReasoning) {this.toolsSuspendReasoning = toolsSuspendReasoning;}

    @Override
    public int getTpm() {return tpm;}

    public void setTpm(int tpm) {this.tpm = tpm;}

    @Override
    public int getRpm() {return rpm;}

    public void setRpm(int rpm) {this.rpm = rpm;}

    @Override
    public Integer getMaxConcurrent() {return maxConcurrent;}

    public void setMaxConcurrent(Integer maxConcurrent) {this.maxConcurrent = maxConcurrent;}

    @Override
    public double getCacheReadMultiplier() {return cacheReadMultiplier;}

    public void setCacheReadMultiplier(double cacheReadMultiplier) {this.cacheReadMultiplier = cacheReadMultiplier;}

    @Override
    public double getCacheWriteMultiplier() {return cacheWriteMultiplier;}

    public void setCacheWriteMultiplier(double cacheWriteMultiplier) {this.cacheWriteMultiplier = cacheWriteMultiplier;}

    @Override
    public String getCurrency() {return currency;}

    public void setCurrency(String currency) {this.currency = currency;}

    @Override
    public Double getInputPricePerMillion() {return inputPricePerMillion;}

    public void setInputPricePerMillion(Double inputPricePerMillion) {this.inputPricePerMillion = inputPricePerMillion;}

    @Override
    public Double getOutputPricePerMillion() {return outputPricePerMillion;}

    public void setOutputPricePerMillion(Double outputPricePerMillion) {this.outputPricePerMillion = outputPricePerMillion;}

    @Override
    public Double getCacheReadPricePerMillion() {return cacheReadPricePerMillion;}

    public void setCacheReadPricePerMillion(Double cacheReadPricePerMillion) {this.cacheReadPricePerMillion = cacheReadPricePerMillion;}

    @Override
    public Double getCacheWritePricePerMillion() {return cacheWritePricePerMillion;}

    public void setCacheWritePricePerMillion(Double cacheWritePricePerMillion) {this.cacheWritePricePerMillion = cacheWritePricePerMillion;}

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ModelSpec other)) {
            return false;
        }
        return Objects.equals(id, other.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + id + " -> " + wireModelId + "]";
    }
}
