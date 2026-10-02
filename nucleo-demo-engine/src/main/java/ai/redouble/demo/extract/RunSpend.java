/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import ai.redouble.nucleo.harness.observability.*;
import java.util.*;

/**
 * What a workflow spent, read off the cost ledger once it is done: the caps it ran under,
 * the totals per currency, the split per model, and the call counts. Every demo report
 * carries one, so a run of any workflow answers "what did that cost" the same way.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public class RunSpend {
    private Map<String, Double> spent = new LinkedHashMap<>();
    private Map<String, Double> caps = new LinkedHashMap<>();
    private List<ExtractReport.ModelUsage> byModel = new ArrayList<>();
    private long llmCalls;
    private long unpricedCalls;

    public static RunSpend of(CostLedger ledger, String workflowId) {
        RunSpend spend = new RunSpend();
        for (Map.Entry<String, Cost> cap : ledger.caps(workflowId).entrySet()) {
            spend.caps.put(cap.getKey(), cap.getValue().amount());
        }
        CostLedger.Workflow workflow = ledger.workflow(workflowId);
        if (workflow != null) {
            for (Map.Entry<String, Cost> total : workflow.getTotals().entrySet()) {
                spend.spent.put(total.getKey(), total.getValue().amount());
            }
            spend.llmCalls = workflow.getCalls().size();
            spend.unpricedCalls = workflow.getUnpricedCalls();
            for (CostLedger.ModelSpend model : workflow.getByModel().values()) {
                ExtractReport.ModelUsage usage = new ExtractReport.ModelUsage();
                usage.setModelId(model.getModelId());
                usage.setCalls(model.getCalls());
                usage.setInputTokens(model.getInputTokens());
                usage.setOutputTokens(model.getOutputTokens());
                usage.setLatencyMs(model.getLatencyMs());
                if (model.getCost() != null) {
                    usage.setCost(model.getCost().amount());
                    usage.setCurrency(model.getCost().currency());
                }
                spend.byModel.add(usage);
            }
        }
        return spend;
    }

    public Map<String, Double> getSpent() {return spent;}

    public void setSpent(Map<String, Double> spent) {this.spent = spent;}

    public Map<String, Double> getCaps() {return caps;}

    public void setCaps(Map<String, Double> caps) {this.caps = caps;}

    public List<ExtractReport.ModelUsage> getByModel() {return byModel;}

    public void setByModel(List<ExtractReport.ModelUsage> byModel) {this.byModel = byModel;}

    public long getLlmCalls() {return llmCalls;}

    public void setLlmCalls(long llmCalls) {this.llmCalls = llmCalls;}

    public long getUnpricedCalls() {return unpricedCalls;}

    public void setUnpricedCalls(long unpricedCalls) {this.unpricedCalls = unpricedCalls;}
}
