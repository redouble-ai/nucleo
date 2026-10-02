/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.harness.llm.*;

import java.util.*;

/**
 * Capability and account metadata for one concrete (provider, model) deployment.
 *
 * <p>Each spec is a single endpoint of a model: the same logical model served on two
 * providers is two specs with distinct {@link #getId() ids} (e.g. {@code claude-opus-5-direct}
 * and {@code claude-opus-5-bedrock}) that share an {@link #getIdentity() identity}
 * ({@code opus-5}). Capability fields (context window, vision, thinking) are intrinsic to
 * the model; {@link #getProviderKey() providerKey}, {@link #getWireModelId() wireModelId},
 * {@link #getTpm() tpm}/{@link #getRpm() rpm}, and the cache multipliers are properties of
 * the endpoint/account this spec represents.
 *
 * <p>Identity is the {@link #getId() id} alone. A spec's tpm/rpm are learned from provider
 * response headers at runtime through {@code Models.updateSpec}, so they are mutable; the id
 * never changes, which is why it is the sole basis of {@code equals}/{@code hashCode} (a spec
 * is a stable map key even as its limits move).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public interface ModelSpec {
    /** Unique catalog key, endpoint-suffixed (e.g. {@code claude-opus-5-bedrock}). */
    String getId();

    /** Logical model shared across provider variants (e.g. {@code opus-5}). */
    String getIdentity();

    /** Key of the {@link ClientProvider} that serves this spec. */
    String getProviderKey();

    /** Provider-specific model identifier sent on the wire. */
    String getWireModelId();

    int getMaxContextTokens();

    int getMaxOutputTokens();

    boolean supportsVision();

    /** Whether the model reads a file sent whole, a PDF as a document; the catalog's {@code supports_documents}. */
    boolean supportsDocuments();

    /** Whether the model accepts the input: images when it supports vision, documents when it supports documents. */
    default boolean accepts(Input input) {
        return switch (input) {
            case IMAGES -> supportsVision();
            case DOCUMENTS -> supportsDocuments();
        };
    }

    /**
     * What the model takes, in the provider's own words ({@code TEXT}, {@code IMAGE},
     * {@code VIDEO}, {@code SPEECH}, ...), as the discovery recorded them from the account's
     * listing; null when never recorded, which every hand-written LLM entry is. The loader
     * reads it to decide whether the entry has a seat, which {@link #hasSeat()} then reflects.
     */
    List<String> getInputModalities();

    /**
     * What the model produces, in the provider's own words ({@code TEXT}, {@code IMAGE},
     * {@code EMBEDDING}, ...), as the discovery recorded them; null when never recorded.
     */
    List<String> getOutputModalities();

    /**
     * Whether the runtime has a seat for this entry: an LLM entry carries a {@link #getGrade()
     * grade}, an embeddings entry rides an embeddings key, a decision entry a decision key. An
     * entry with none is one no picker, pool or benchmark lands on - a model the account offers
     * in a modality the runtime has no client for (image generation, speech, video), written by
     * the discovery with its modalities so the catalog states what the account has, or a text
     * entry a person wrote without a grade, reachable by id alone.
     */
    default boolean hasSeat() {
        return getGrade() != null || isEmbeddings() || isDecision();
    }

    ThinkingMode getThinkingMode();

    /**
     * The reasoning tokens a call at this depth books on this entry. Two sources: the entry's
     * own {@code thinking_budgets} declaration in the catalog (the four non-IMMEDIATE depths for
     * an Anthropic mode, all five for a reasoning-effort model, or none), else the framework
     * ladder scaled to the output ceiling (QUICK a sixteenth, STANDARD an eighth, THOROUGH and
     * ULTRA_THOROUGH a quarter, floor 1024; IMMEDIATE the floor on a reasoning-effort model and
     * zero otherwise). The declaration is a fine-tuning knob for the entry that knows better;
     * the ladder works without it.
     *
     * <p>What the number does depends on {@link #getThinkingMode()}: for EXTENDED it is the
     * {@code budget_tokens} sent and the reservation; for ADAPTIVE the provider picks its own
     * budget from the effort word and the number is wire headroom plus reservation; for
     * REASONING_EFFORT the model reasons inside the ordinary completion ceiling, so the number
     * is the headroom added above the answer and the reservation, at every depth including
     * IMMEDIATE (which maps to the lowest effort, never to no reasoning). For NONE, and for the
     * Anthropic modes at IMMEDIATE, it is zero.
     */
    int getThinkingBudget(Depth depth);

    /**
     * The output tokens an {@link OutputSize} rung means on this entry. Two sources: the entry's
     * own {@code output_budgets} declaration in the catalog (all four sized rungs, or none),
     * else the framework table; either way capped at {@link #getMaxOutputTokens()}, and
     * {@link OutputSize#MAX} is the ceiling itself. A rung is the seat's vocabulary; this is
     * the entry's translation, which may differ per entry because tokenizers differ in density.
     */
    int getOutputBudget(OutputSize size);

    /**
     * The prompt size past which this entry's answers are observed to degrade, or null when the
     * entry declares none. An empirical number we own, distinct from the provider's hard
     * {@link #getMaxContextTokens()}; {@code ContextWindowManager} resolves the window as
     * thinker override, then this, then its framework default.
     */
    Integer getComfortContextTokens();

    /**
     * This identity's rung on the capability ladder, or null for embeddings specs, which
     * are not on the ladder. Stamped per entry in {@code models.json} and identity-uniform
     * across endpoint variants; the same curation judgment as {@link #getStatus()}.
     * The resolution gate refuses an LLM spec below a seat's declared floor.
     */
    Grade getGrade();

    /**
     * Whether this spec is an embeddings model - a different client family entirely,
     * never on the capability ladder. Keyed off the provider registry's naming
     * convention (embeddings {@link ClientProvider}s are registered under
     * {@code *-embeddings} keys); {@link #getEmbeddingDimensions()} is NOT a kind
     * marker - it is null for embeddings specs that rely on the framework canonical
     * dimension.
     */
    default boolean isEmbeddings() {
        return getProviderKey() != null && ModelKind.ofProviderKey(getProviderKey()) == ModelKind.EMBEDDINGS;
    }

    /**
     * Whether this spec is a decision model - the third client family, in the shape of
     * TypeSafe's Jev and its open replicas: state and typed questions in, a probability per
     * declared option out, no text generated. Keyed off the provider registry's naming
     * convention exactly as {@link #isEmbeddings()} is: decision {@link ClientProvider}s are
     * registered under {@code *-decision} keys. Never on the capability ladder.
     */
    default boolean isDecision() {
        return getProviderKey() != null && ModelKind.ofProviderKey(getProviderKey()) == ModelKind.DECISION;
    }

    /** The client family this entry belongs to; see {@link ModelKind}. */
    default ModelKind kind() {
        if (isEmbeddings()) {
            return ModelKind.EMBEDDINGS;
        }
        if (isDecision()) {
            return ModelKind.DECISION;
        }
        return ModelKind.LLM;
    }

    /**
     * How many requests this endpoint takes in flight at once, or null when the endpoint is
     * bounded by a quota window instead. A hosted model publishes tokens and requests per
     * minute ({@link #getTpm()}, {@link #getRpm()}) and is accounted by a token bucket that
     * the clock refills. A model served from a machine the deployment owns has no quota: its
     * bound is how many requests its server works on at once, one for a server that runs
     * them serially, and past that callers only queue. Such an entry declares this instead,
     * and admission accounts it as a counting gate held for the request, the same account a
     * database pool is. An entry declares one bound or the other, never both.
     */
    Integer getMaxConcurrent();

    /**
     * Whether this spec is served from AWS Bedrock, on any of its surfaces (native
     * anthropic-bedrock, Mantle, Converse). The overload-failover default confines
     * itself to Bedrock-hosted routes - a provider-key fact, same convention as
     * {@link #isEmbeddings()}.
     */
    default boolean isBedrockHosted() {
        return getProviderKey() != null && getProviderKey().contains("bedrock");
    }

    /**
     * Native output dimension this embeddings endpoint is pinned to, or null for LLM specs
     * and for embeddings specs that rely on the framework canonical dimension. When present,
     * {@link AbstractEmbeddingsClient} coerces to this dimension instead of
     * {@code EmbeddingsClient.DIMENSIONS}, so a native-width vector is never round-tripped
     * through a lossy expand/fold against the canonical width.
     */
    Integer getEmbeddingDimensions();

    /**
     * Whether this spec may be served, and who said it may not: {@link ModelStatus#OPEN} is
     * picked and pinged, {@link ModelStatus#DISABLED} is the deployment's own decision and
     * {@link ModelStatus#DEPRECATED} the vendor's.
     *
     * <p>Neither closed status removes the entry: the call record holds the id of whatever
     * served each logged call, and {@code Models.findSpec} reverse-looks-up that id to price it.
     * Dropping an entry would silently unprice every historical row that used it. So the spec
     * stays resolvable for billing and replay, while the resolution gate
     * ({@code ModelPickers.resolve}) refuses to serve it and no picker routes new traffic there.
     */
    ModelStatus getStatus();

    /**
     * A person's or the discovery's free-text remark on the entry, or null: where it came
     * from, what was inherited, why it is disabled. Never read by the runtime.
     */
    String getNote();

    /**
     * Whether this model cannot run at zero retention: its {@code allowed_modes} exclude
     * {@code none}, so the STRICT project's mode is refused for it and the only project the runtime
     * can route it through is the LAX one, whose mode is {@code provider_data_share}. The Mantle
     * client refuses to route such a spec unless the application has opted in and the deployment
     * has a LAX project configured; see {@code AnthropicBedrockMantleSDKClient}. Fable 5 today
     * allows {@code aws_review} and {@code provider_data_share} and not {@code none}.
     *
     * <p>This is a property of the (model, account) pair and changes over time in both directions:
     * new sharing-required models arrive with it set, and an account granted ZDR eligibility for a
     * model by the provider clears it. Ground truth is the model's {@code allowed_modes} in the
     * Bedrock Mantle catalog for the deployment's account.
     */
    boolean requiresLax();

    /**
     * Whether the entry was written by the discovery's inference and no person has confirmed it:
     * a classifier's judgment, or a shape inherited from an older relative. Its grade and prices
     * are then the runtime's best guess, shown as such until a person edits or confirms them
     * ({@code unverified} in the catalog file). An entry a person wrote, a shipped fragment's
     * entry and a confirmed one carry {@code false}.
     */
    boolean unverified();

    /**
     * Whether a turn that carries tools must go out without a reasoning effort: the model's
     * surface refuses the two together ({@code 400 Function tools with reasoning_effort are not
     * supported ... set reasoning_effort to 'none'}), so a client sends {@code none} on those
     * turns and the seat's depth on the rest. A fact of the model on that surface, declared in
     * the catalog ({@code tools_suspend_reasoning}); the GPT-5.6 family on Chat Completions is the
     * first with it, on OpenAI and on Foundry alike, where GPT-5 took both.
     */
    boolean toolsSuspendReasoning();

    /** Tokens-per-minute limit for this endpoint/account. Seeded from the catalog, learned at runtime. */
    int getTpm();

    /** Requests-per-minute limit for this endpoint/account. Seeded from the catalog, learned at runtime. */
    int getRpm();

    /** Billing multiplier applied to cache-read input tokens for this endpoint. */
    double getCacheReadMultiplier();

    /** Billing multiplier applied to cache-creation input tokens for this endpoint. */
    double getCacheWriteMultiplier();

    /**
     * The currency every price on this entry is stated in, as an ISO 4217 code ({@code USD},
     * {@code EUR}, {@code CNY}), or null for an entry that carries no price. No provider reports
     * cost on the wire, only tokens; a price is what a person typed from a list page or an
     * invoice, in whatever currency that page bills in. The runtime accounts cost per call in
     * the entry's currency and never converts: a rollup sums per currency, and a cap stated in
     * one currency refuses a call priced in another rather than guessing a rate.
     */
    String getCurrency();

    /**
     * List price per million input tokens for this endpoint in {@link #getCurrency()}, or null
     * when no price is configured. The rate applies to the base (uncached) input token. Bedrock
     * and direct variants of the same model can differ, which is why pricing lives per spec id,
     * not per identity.
     */
    Double getInputPricePerMillion();

    /** List price per million output tokens for this endpoint in {@link #getCurrency()}, or null when unset. */
    Double getOutputPricePerMillion();

    /**
     * List price per million cache-read input tokens, or null to derive it from
     * {@link #getInputPricePerMillion()} x {@link #getCacheReadMultiplier()}. Set explicitly only
     * when the provider's cache-read price is not that simple multiple of the base input rate
     * (e.g. OpenAI's GPT-5 family caches at 0.1x while its billing multiplier is 0.5).
     */
    Double getCacheReadPricePerMillion();

    /**
     * List price per million cache-write (cache-creation) input tokens, or null to derive it
     * from {@link #getInputPricePerMillion()} x {@link #getCacheWriteMultiplier()}.
     */
    Double getCacheWritePricePerMillion();

    /**
     * Whether the entry carries what a call's cost needs: a currency and an input price, and
     * an output price unless the entry is an embeddings or a decision model, whose calls have
     * no output the provider bills.
     */
    default boolean isPriced() {
        return getInputPricePerMillion() != null && getCurrency() != null
                && (getOutputPricePerMillion() != null || isEmbeddings() || isDecision());
    }

    /**
     * Cache-read billing weight relative to the base input rate, used both for billable-token
     * accounting and cost. When an explicit cache-read price is configured this is the exact ratio
     * {@code cacheReadPricePerMillion / inputPricePerMillion}; otherwise it falls back to the stored
     * {@link #getCacheReadMultiplier()}. The explicit price is authoritative when present, so this
     * is the single source of truth that keeps token accounting and cost consistent.
     */
    default double getEffectiveCacheReadMultiplier() {
        Double cache = getCacheReadPricePerMillion();
        Double in = getInputPricePerMillion();
        return cache != null && in != null && in > 0.0 ? cache / in : getCacheReadMultiplier();
    }

    /** Cache-write billing weight relative to the base input rate; see {@link #getEffectiveCacheReadMultiplier()}. */
    default double getEffectiveCacheWriteMultiplier() {
        Double cache = getCacheWritePricePerMillion();
        Double in = getInputPricePerMillion();
        return cache != null && in != null && in > 0.0 ? cache / in : getCacheWriteMultiplier();
    }
}
