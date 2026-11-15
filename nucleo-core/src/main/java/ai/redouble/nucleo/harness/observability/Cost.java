/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.tokens.*;

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
     * What one call's ledger costs on the model it counts on: the model's own rule,
     * {@link ModelSpec#cost}, asked of the ledger's spec. Null when the model carries no
     * price, which a rollup reports as unpriced rather than as zero.
     */
    public static Cost of(TokenLedger ledger) {
        return ledger.spec().cost(ledger);
    }

    @Override
    public String toString() {
        return String.format("%.6f %s", amount, currency);
    }
}
