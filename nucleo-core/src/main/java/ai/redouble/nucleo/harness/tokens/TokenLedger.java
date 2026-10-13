/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;

import java.util.*;

/**
 * One call's tokens by kind, on one model. The same ledger describes a call before it is
 * made, as the estimate {@link ModelSpec#estimate} returns and the reservation holds, and
 * after it returns, as the usage the provider reported on the {@code LLMResponse}. The
 * provider module that serves the model builds both, because both need the provider's
 * knowledge: how its tokenizer counts, which blocks it bills, how it reports its cache.
 * Method names carry no "estimate" or "actual"; provenance is a property of the instance.
 *
 * <p>Two absences, kept apart:
 * <ul>
 *   <li>A facet the provider does not report is {@code null}: a usage record without cache
 *       counters leaves the cache facets null, and a provider that returns no usage record gives
 *       the response no ledger at all. A zero is a fact: OpenAI bills no cache writes, so its
 *       ledger says zero.</li>
 *   <li>A facet a ledger does not implement refuses with {@link UnsupportedOperationException}
 *       naming the provider and the facet. Every facet refuses by default, here, so a facet
 *       added to this interface later is a default method and no implementation breaks.</li>
 * </ul>
 * Derived facets ({@link #uncachedInputTokens}, {@link #cacheHitRate}, {@link #cost}) are null
 * when an operand they need is null; a provider that knows better overrides them.
 *
 * <p>Which facet each check reads: compaction reads {@link #inputTokens} alone, against the
 * effective limit (the smaller of the comfort window and the model's window) times the trigger
 * and then against the model's window, because compaction
 * shrinks input and nothing else; the client's pre-flight reads {@link #inputTokens} plus
 * {@link #outputTokens} against the window, since the call must fit whole; the reservation
 * takes {@link #meteredTokens}, whatever the model's limit meters.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
public interface TokenLedger {
    /** The model this ledger counts on. */
    ModelSpec spec();

    /** Total input the model reads: uncached input, cache writes and cache reads together. */
    default Integer inputTokens() {
        throw unimplemented("inputTokens");
    }

    /** Input tokens read from the provider's cache. */
    default Integer cacheReadTokens() {
        throw unimplemented("cacheReadTokens");
    }

    /** Input tokens written to the provider's cache. */
    default Integer cacheWriteTokens() {
        throw unimplemented("cacheWriteTokens");
    }

    /**
     * Output tokens. On an estimate, the reserve the call books: the declared answer plus the
     * depth's thinking budget, clamped at the model's output ceiling, resolved from the seat's
     * declarations the first time it is read, so an estimate of the input alone never needs
     * them. On a reported usage, what the model generated, thinking included where the
     * provider bills it as output. Zero on a call that generates nothing, an embedding or a
     * decision.
     */
    default Integer outputTokens() {
        throw unimplemented("outputTokens");
    }

    /**
     * What the model's per-minute limit meters for this call, the amount a reservation takes
     * from the limiter and a reported usage gives back to it. Which tokens a limit counts is the
     * provider's rule: one meters input and output together, another meters them on separate
     * limits or excludes what its cache served. The default is input plus output; a provider's
     * ledger overrides it with its own rule.
     */
    default Integer meteredTokens() {
        throw unimplemented("meteredTokens");
    }

    /** Input tokens billed at the full input price: input less cache reads and cache writes. */
    default Integer uncachedInputTokens() {
        throw unimplemented("uncachedInputTokens");
    }

    /** Cache reads over cache reads plus cache writes; 1.0 is a fully warm cache, 0.0 a cache being built. */
    default Double cacheHitRate() {
        throw unimplemented("cacheHitRate");
    }

    /**
     * What this ledger costs at the model's list prices, in the model's currency, or null when
     * the model carries no price. An estimate has no cache facets, so every input token is
     * priced at the full rate: its cost is the ceiling the call can reach. A reported usage
     * missing a cache facet is priced on what was reported: a floor.
     */
    default Cost cost() {
        throw unimplemented("cost");
    }

    /**
     * On an estimate, the input by {@link ContextPart}, summing to {@link #inputTokens}; it is
     * what a refusal names when a conversation cannot be made to fit. A reported usage has no
     * parts.
     */
    default Map<ContextPart, Integer> inputByPart() {
        throw unimplemented("inputByPart");
    }

    private UnsupportedOperationException unimplemented(String facet) {
        return new UnsupportedOperationException(spec().getProviderKey() + " ledger does not implement " + facet);
    }
}
