/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;

import java.util.*;
import java.util.function.*;

/**
 * The ledger every provider extends: the counts by kind, fixed at construction except an
 * estimate's output reserve, which is resolved the first time it is read and then fixed, with
 * the derived facets computed from them. A provider's subclass normalizes its own usage record
 * into these counts in its constructor (Anthropic adds cache tokens on top of its input
 * figure, OpenAI reports them inside it) and overrides a derived facet only when it knows
 * more than the arithmetic here.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
public abstract class AbstractTokenLedger implements TokenLedger {
    private final ModelSpec spec;
    private final Integer inputTokens;
    private final Integer cacheReadTokens;
    private final Integer cacheWriteTokens;
    private final IntSupplier outputReserve;
    private volatile Integer outputTokens;
    private final Map<ContextPart, Integer> inputByPart;

    /**
     * A reported usage: the counts as the provider stated them, a facet it did not report
     * left null.
     */
    protected AbstractTokenLedger(ModelSpec spec, Integer inputTokens, Integer cacheReadTokens, Integer cacheWriteTokens, Integer outputTokens) {
        if (spec == null) {
            throw new IllegalArgumentException("A token ledger counts on a model; the spec is required");
        }
        this.spec = spec;
        this.inputTokens = inputTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.cacheWriteTokens = cacheWriteTokens;
        this.outputReserve = null;
        this.outputTokens = outputTokens;
        this.inputByPart = null;
    }

    /**
     * An estimate: the input by part, summing to the input, and the output reserve the call
     * will book, resolved from the seat's declarations the first time {@link #outputTokens}
     * is read. A reader of the input alone, compaction, never needs the declarations; a reader
     * of the reserve meets the seat's refusal when it declared none, as the call itself would.
     * Cache facets are not known before the call and stay null. The parts are copied, so a later
     * change to the caller's map does not reach the ledger, and handed out read-only; an estimate
     * without its parts or without a way to resolve its reserve is refused.
     */
    protected AbstractTokenLedger(ModelSpec spec, Map<ContextPart, Integer> inputByPart, IntSupplier outputReserve) {
        if (spec == null) {
            throw new IllegalArgumentException("A token ledger counts on a model; the spec is required");
        }
        if (inputByPart == null || outputReserve == null) {
            throw new IllegalArgumentException("An estimate states its input by part and how to resolve its output reserve");
        }
        this.spec = spec;
        int input = 0;
        for (Integer part : inputByPart.values()) {
            input += part;
        }
        this.inputTokens = input;
        this.cacheReadTokens = null;
        this.cacheWriteTokens = null;
        this.outputReserve = outputReserve;
        this.inputByPart = Collections.unmodifiableMap(new EnumMap<>(inputByPart));
    }

    @Override
    public ModelSpec spec() {
        return spec;
    }

    @Override
    public Integer inputTokens() {
        return inputTokens;
    }

    @Override
    public Integer cacheReadTokens() {
        return cacheReadTokens;
    }

    @Override
    public Integer cacheWriteTokens() {
        return cacheWriteTokens;
    }

    @Override
    public Integer outputTokens() {
        Integer output = outputTokens;
        if (output == null && outputReserve != null) {
            output = outputReserve.getAsInt();
            outputTokens = output;
        }
        return output;
    }

    @Override
    public Integer meteredTokens() {
        Integer output = outputTokens();
        if (inputTokens == null || output == null) {
            return null;
        }
        return inputTokens + output;
    }

    @Override
    public Integer uncachedInputTokens() {
        if (inputTokens == null || cacheReadTokens == null || cacheWriteTokens == null) {
            return null;
        }
        return inputTokens - cacheReadTokens - cacheWriteTokens;
    }

    @Override
    public Double cacheHitRate() {
        if (cacheReadTokens == null || cacheWriteTokens == null) {
            return null;
        }
        int reads = cacheReadTokens;
        int writes = cacheWriteTokens;
        if (reads + writes == 0) {
            return 0.0;
        }
        return (double)reads / (reads + writes);
    }

    @Override
    public Cost cost() {
        if (inputTokens == null) {
            return null;
        }
        long cacheWrite = cacheWriteTokens != null ? cacheWriteTokens : 0;
        long cacheRead = cacheReadTokens != null ? cacheReadTokens : 0;
        Integer resolvedOutput = outputTokens();
        long output = resolvedOutput != null ? resolvedOutput : 0;
        return Cost.of(spec, inputTokens, cacheWrite, cacheRead, output);
    }

    @Override
    public Map<ContextPart, Integer> inputByPart() {
        if (inputByPart == null) {
            throw new UnsupportedOperationException(spec.getProviderKey() + " ledger of a reported usage has no input by part");
        }
        return inputByPart;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + spec.getId() + " in=" + inputTokens + " cacheRead=" + cacheReadTokens
                + " cacheWrite=" + cacheWriteTokens + " out=" + (outputTokens != null ? outputTokens : "unresolved")
                + (inputByPart != null ? " parts=" + inputByPart : "") + "}";
    }
}
