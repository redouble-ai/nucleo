/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.harness.models.*;

/**
 * An amount in a currency: three upper-case letters, the ISO 4217 code, refused at construction
 * otherwise. Two costs add only in the same currency, and {@link #exceeds} compares only in the
 * same currency, refusing any other with {@link IllegalArgumentException}; the runtime never
 * converts, because a rate is not its business and a figure that quietly mixed two currencies
 * would be wrong in both. {@code exceeds} is strictly past the cap: an amount equal to the cap
 * is not past it. {@link #toString()} renders six decimals and the currency.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public record Cost(double amount, String currency) {
    private static final double MILLION = 1_000_000.0;
    /**
     * Money is exact to a billionth of its unit. Token prices are quoted per million tokens to
     * a few decimals, so every real cost is a whole number of nano-units, and rounding there
     * keeps binary noise from summing thousands of calls out of every figure a cap or a
     * person reads.
     */
    private static final double NANO = 1_000_000_000.0;

    public Cost {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("A cost needs an ISO 4217 currency, got '" + currency + "'");
        }
        amount = Math.round(amount * NANO) / NANO;
    }

    public Cost plus(Cost other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot add " + other + " to " + this + ": different currencies are never summed");
        }
        return new Cost(amount + other.amount, currency);
    }

    public boolean exceeds(Cost cap) {
        if (!currency.equals(cap.currency)) {
            throw new IllegalArgumentException("Cannot compare " + this + " with a cap in " + cap.currency);
        }
        return amount > cap.amount;
    }

    /**
     * What a call costs on a priced entry: uncached input at the input price, cache writes
     * and reads at the entry's effective cache rates, output at the output price. Null when
     * the entry carries no price, which a rollup reports as unpriced rather than as zero.
     */
    public static Cost of(ModelSpec spec, long inputTokens, long cacheWriteTokens, long cacheReadTokens, long outputTokens) {
        if (!spec.isPriced()) {
            return null;
        }
        double in = spec.getInputPricePerMillion();
        // an embeddings entry prices input alone; its calls produce no billed output
        double out = spec.getOutputPricePerMillion() != null ? spec.getOutputPricePerMillion() : 0.0;
        long uncached = inputTokens - cacheWriteTokens - cacheReadTokens;
        double amount = uncached / MILLION * in
                + cacheWriteTokens / MILLION * in * spec.getEffectiveCacheWriteMultiplier()
                + cacheReadTokens / MILLION * in * spec.getEffectiveCacheReadMultiplier()
                + outputTokens / MILLION * out;
        return new Cost(amount, spec.getCurrency());
    }

    /** What a reservation would cost at most: every reserved token at its own price, none of it cached. */
    public static Cost reserved(ModelSpec spec, long inputTokens, long outputTokens) {
        if (!spec.isPriced()) {
            return null;
        }
        double out = spec.getOutputPricePerMillion() != null ? spec.getOutputPricePerMillion() : 0.0;
        return new Cost(inputTokens / MILLION * spec.getInputPricePerMillion() + outputTokens / MILLION * out, spec.getCurrency());
    }

    @Override
    public String toString() {
        return String.format("%.6f %s", amount, currency);
    }
}
