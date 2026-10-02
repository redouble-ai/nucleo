/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Default {@link TokenizerFactory}. Resolves a {@link TokenCounter} per {@link ModelSpec}
 * by provider family (Anthropic and Bedrock-Converse models use the Claude counter, Gemini
 * models a Gemini-calibrated counter, Cohere models a Cohere-calibrated counter, OpenAI
 * models use tiktoken), memoizing the chosen
 * counter per spec on first use. {@link #register}
 * overrides a specific model. The per-family calibration numbers (multipliers, image base
 * tokens, PDF tokens per KB) are {@link Cl100kTokenCounter}'s own measured constants.
 *
 * <p>Selecting by {@link ModelSpec#getProviderKey() provider key} rather than the model name
 * is deliberate: a Bedrock-served Claude carries a wire id like {@code us.anthropic.claude-...}
 * that a name-prefix check would misclassify, while the provider key is unambiguous.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-17)
 */
public class DefaultTokenizerFactory implements TokenizerFactory {
    private final Supplier<TokenCounter> claude = memoize(DefaultTokenizerFactory::buildClaude);
    private final Supplier<TokenCounter> tiktoken = memoize(DefaultTokenizerFactory::buildTiktoken);
    private final Supplier<TokenCounter> gemini = memoize(DefaultTokenizerFactory::buildGemini);
    private final Supplier<TokenCounter> cohere = memoize(DefaultTokenizerFactory::buildCohere);
    private final Map<ModelSpec, Supplier<TokenCounter>> overrides = new ConcurrentHashMap<>();
    private final Map<ModelSpec, TokenCounter> counters = new ConcurrentHashMap<>();

    @Override
    public TokenCounter forModel(ModelSpec model) {
        if (model == null) {
            throw new IllegalArgumentException("model cannot be null");
        }
        return counters.computeIfAbsent(model, m -> supplierFor(m).get());
    }

    @Override
    public void register(ModelSpec model, Supplier<TokenCounter> counterSupplier) {
        if (model == null) {
            throw new IllegalArgumentException("model cannot be null");
        }
        if (counterSupplier == null) {
            throw new IllegalArgumentException("counterSupplier cannot be null");
        }
        overrides.put(model, memoize(counterSupplier));
        counters.remove(model);
    }

    private Supplier<TokenCounter> supplierFor(ModelSpec model) {
        Supplier<TokenCounter> override = overrides.get(model);
        if (override != null) {
            return override;
        }
        String providerKey = model.getProviderKey();
        if (providerKey.startsWith("anthropic") || providerKey.equals("bedrock-converse")) {
            return claude;
        }
        if (providerKey.startsWith("gemini")) {
            return gemini;
        }
        if (providerKey.contains("cohere")) {
            return cohere;
        }
        return tiktoken;
    }

    private static TokenCounter buildClaude() {
        return new Cl100kTokenCounter(
            Cl100kTokenCounter.DEFAULT_MULTIPLIER_CLAUDE,
            Cl100kTokenCounter.DEFAULT_IMAGE_BASE_TOKENS_CLAUDE,
            0,
            Cl100kTokenCounter.DEFAULT_PDF_TOKENS_PER_KB);
    }

    private static TokenCounter buildTiktoken() {
        return new Cl100kTokenCounter(
            Cl100kTokenCounter.DEFAULT_MULTIPLIER_TIKTOKEN,
            Cl100kTokenCounter.DEFAULT_IMAGE_BASE_TOKENS_TIKTOKEN,
            Cl100kTokenCounter.DEFAULT_IMAGE_PER_TILE_TOKENS_TIKTOKEN,
            Cl100kTokenCounter.DEFAULT_PDF_TOKENS_PER_KB);
    }

    private static TokenCounter buildCohere() {
        return new Cl100kTokenCounter(
            Cl100kTokenCounter.DEFAULT_MULTIPLIER_COHERE,
            Cl100kTokenCounter.DEFAULT_IMAGE_BASE_TOKENS_TIKTOKEN,
            Cl100kTokenCounter.DEFAULT_IMAGE_PER_TILE_TOKENS_TIKTOKEN,
            Cl100kTokenCounter.DEFAULT_PDF_TOKENS_PER_KB);
    }

    private static TokenCounter buildGemini() {
        return new Cl100kTokenCounter(
            Cl100kTokenCounter.DEFAULT_MULTIPLIER_GEMINI,
            Cl100kTokenCounter.DEFAULT_IMAGE_BASE_TOKENS_GEMINI,
            0,
            Cl100kTokenCounter.DEFAULT_PDF_TOKENS_PER_KB);
    }

    private static <T> Supplier<T> memoize(Supplier<T> delegate) {
        return new Supplier<>() {
            private volatile T value;
            private volatile boolean resolved;
            @Override
            public T get() {
                if (!resolved) {
                    synchronized (this) {
                        if (!resolved) {
                            value = delegate.get();
                            resolved = true;
                        }
                    }
                }
                return value;
            }
        };
    }
}
