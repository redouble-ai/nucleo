/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import java.util.function.*;

/**
 * Resolves a {@link TokenCounter} for any {@link ModelSpec}. The contract is
 * deliberately narrow: given a model, return a counter. Always returns something.
 * <p>
 * Implementations may source counters per-provider, per-model, per-customer;
 * use remote or local; prefer accuracy or speed. The factory is the single
 * seam for that policy. Registration lets callers override specific models
 * without subclassing.
 * <p>
 * The process-wide instance is accessed through {@link #get()}. Replace it via
 * {@link #set(TokenizerFactory)} at startup if you want a custom policy.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-17)
 */
public interface TokenizerFactory {

    TokenCounter forModel(ModelSpec model);

    void register(ModelSpec model, Supplier<TokenCounter> counterSupplier);

    static TokenizerFactory get() {
        return Holder.INSTANCE;
    }

    static void set(TokenizerFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("factory cannot be null");
        }
        Holder.INSTANCE = factory;
    }

    final class Holder {
        private static volatile TokenizerFactory INSTANCE = new DefaultTokenizerFactory();
        private Holder() {}
    }
}
