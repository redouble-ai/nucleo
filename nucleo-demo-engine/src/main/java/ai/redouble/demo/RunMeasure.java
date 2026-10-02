/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;

import java.util.*;

/**
 * What a run has spent so far, summed from the responses its terminal events carry - the
 * run's own and its calls' - priced from the catalog the way the cost ledger prices them,
 * so the numbers move while the run is on and the ledger's figures replace them when it
 * lands. One instance per run; the benchmark race keeps one per row, the agent trace one
 * for the whole run. {@link #call(LLMResponse)} is the same accounting for a single model
 * call, so a timeline can price each turn on its own line.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
final class RunMeasure {
    private long calls;
    private long inputTokens;
    private long outputTokens;
    private long latencyMs;
    private Cost cost;
    private String servedModel;
    private Long wallMs;

    /** The run's totals after this terminal event: its own responses added, and its wall time when the event is the run's own. */
    synchronized Map<String, Object> add(AbstractTerminalEvent<?> terminal, boolean own) {
        for (LLMResponse<?> response : terminal.getLlmResponses()) {
            calls++;
            inputTokens += tokens(response.getActualInputTokens());
            outputTokens += tokens(response.getActualOutputTokens());
            latencyMs += response.getLatencyMs();
            Cost priced = price(response);
            if (priced != null) {
                cost = cost == null ? priced : cost.plus(priced);
            }
            if (response.getServedModelId() != null) {
                servedModel = response.getServedModelId();
            }
        }
        if (own) {
            wallMs = terminal.getDuration().toMillis();
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("calls", calls);
        totals.put("inputTokens", inputTokens);
        totals.put("outputTokens", outputTokens);
        totals.put("latencyMs", latencyMs);
        totals.put("cost", cost != null ? cost.amount() : null);
        totals.put("currency", cost != null ? cost.currency() : null);
        totals.put("servedModel", servedModel);
        totals.put("wallMs", wallMs);
        return totals;
    }

    /** One model call as its own line: the entry and the model that served it, its tokens, latency, price and stop reason. */
    static Map<String, Object> call(LLMResponse<?> response) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("model", response.getModel());
        line.put("servedModel", response.getServedModelId());
        line.put("inputTokens", tokens(response.getActualInputTokens()));
        line.put("cacheWriteTokens", tokens(response.getCacheCreationInputTokens()));
        line.put("cacheReadTokens", tokens(response.getCacheReadInputTokens()));
        line.put("outputTokens", tokens(response.getActualOutputTokens()));
        line.put("latencyMs", response.getLatencyMs());
        Cost priced = price(response);
        line.put("cost", priced != null ? priced.amount() : null);
        line.put("currency", priced != null ? priced.currency() : null);
        line.put("stopReason", response.getStopReason() != null ? response.getStopReason().name() : null);
        line.put("attempts", response.getAttempts());
        return line;
    }

    /** Priced from the catalog entry the call was made under; null when the entry is unknown or carries no price. */
    static Cost price(LLMResponse<?> response) {
        ModelSpec spec = response.getModel() != null ? Models.findSpec(response.getModel()) : null;
        if (spec == null) {
            return null;
        }
        return Cost.of(spec, tokens(response.getActualInputTokens()), tokens(response.getCacheCreationInputTokens()),
                tokens(response.getCacheReadInputTokens()), tokens(response.getActualOutputTokens()));
    }

    private static long tokens(Integer count) {
        return count != null ? count : 0;
    }
}
