/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.util.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Static facade over the model catalog, mirroring {@code ai.redouble.nucleo.prompt.Prompts}: a
 * pluggable {@link ModelsBackend} (DEFAULTS) under a per-id override layer and a resolution
 * cache.
 *
 * <p>Resolution order for {@link #findSpec(String)}: OVERRIDES -> CACHE -> backend, with the
 * override layer consulted again by canonical id after any cache or backend hit - so an
 * override wins whether the lookup came in by canonical id or by wire id, and a cache entry
 * re-filled concurrently with an update can never shadow it. The backend resolves by
 * canonical id or wire id. {@link #spec(String)} throws {@link ModelNotFoundException} on a
 * miss; {@link #findSpec(String)} returns null for the null-tolerant billing path.
 *
 * <p>This is pure catalog: what exists, what it can do, what it costs. WHO gets a model is
 * the {@link ModelPicker}'s business, consulted by the harness through {@link ModelPickers} -
 * there are no tier accessors here. {@link #pool(Grade)}, {@link #embeddingsPool()} and
 * {@link #decisionPool()} hand policies their candidate universes. Client construction is the llm package's business:
 * {@code ClientProviders.llmClient(spec)} / {@code embeddingsClient(spec)} return the bare,
 * app-shared client, and the per-job {@code ObservableLLMClient} wrap lives in
 * {@code JobResources} - neither here.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public final class Models {
    private static volatile ModelsBackend backend;
    private static final Map<String, ModelSpec> OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<String, ModelSpec> CACHE = new ConcurrentHashMap<>();

    private Models() {}

    /** Resolves a spec by id or wire id, throwing if none matches. */
    public static ModelSpec spec(String id) {
        ModelSpec s = findSpec(id);
        if (s == null) {
            throw new ModelNotFoundException(id);
        }
        return s;
    }

    /** Resolves a spec by id or wire id, returning null if none matches. */
    public static ModelSpec findSpec(String id) {
        if (id == null) {
            return null;
        }
        // Overrides are consulted BEFORE the cache and again after any backend hit: the
        // override layer holds learned limits keyed by canonical id, so a lookup that came
        // in by wire id, or a cache entry re-filled concurrently with an update, must still
        // land on the override rather than the backend's seeded spec.
        ModelSpec override = OVERRIDES.get(id);
        if (override != null) {
            return override;
        }
        ModelSpec cached = CACHE.get(id);
        if (cached != null) {
            ModelSpec canonical = OVERRIDES.get(cached.getId());
            return canonical != null ? canonical : cached;
        }
        ModelSpec s = backend().spec(id);
        if (s != null) {
            ModelSpec canonical = OVERRIDES.get(s.getId());
            if (canonical != null) {
                return canonical;
            }
            CACHE.putIfAbsent(s.getId(), s);
        }
        return s;
    }

    public static Collection<ModelSpec> all() {
        return backend().all();
    }

    /** The deployment's catalog pins, or null when its catalog source declares none. */
    public static CatalogPins pins() {
        return backend().pins();
    }

    /** The provider-variant specs sharing a logical {@link ModelSpec#getIdentity() identity}. */
    public static Collection<ModelSpec> variants(String identity) {
        Map<String, ModelSpec> byId = new LinkedHashMap<>();
        for (ModelSpec s : backend().all()) {
            if (Objects.equals(identity, s.getIdentity())) {
                byId.put(s.getId(), s);
            }
        }
        for (ModelSpec s : OVERRIDES.values()) {
            if (Objects.equals(identity, s.getIdentity())) {
                byId.put(s.getId(), s);
            }
        }
        return byId.values();
    }

    /**
     * Records a spec's current factual limits (learned from provider rate-limit response
     * headers). Sole write channel: replaces the stored spec in the override layer and
     * refreshes the derived rate limiter so the bucket and the catalog record never diverge.
     */
    public static void updateSpec(String id, ModelSpec spec) {
        CACHE.remove(id);
        OVERRIDES.put(id, spec);
        RateLimiterRegistry.getInstance().updateLimits(spec, spec.getRpm(), spec.getTpm());
    }

    /**
     * The candidate pool for a grade: every catalog spec whose grade IS the argument - the
     * rung only. An over-qualified model is capable but not a candidate; a policy that
     * moves up asks for the higher rung by name.
     */
    public static Collection<ModelSpec> pool(Grade grade) {
        if (!grade.isRung()) {
            throw new IllegalArgumentException("Grade.CEILING is not a rung and has no pool; it is resolved at the picker gate");
        }
        Map<String, ModelSpec> byId = new LinkedHashMap<>();
        for (ModelSpec s : backend().all()) {
            if (s.getGrade() == grade) {
                byId.put(s.getId(), s);
            }
        }
        for (ModelSpec s : OVERRIDES.values()) {
            if (s.getGrade() == grade) {
                byId.put(s.getId(), s);
            }
        }
        return byId.values();
    }

    /** The embeddings candidate pool: every embeddings-family spec in the catalog. */
    public static Collection<ModelSpec> embeddingsPool() {
        Map<String, ModelSpec> byId = new LinkedHashMap<>();
        for (ModelSpec s : backend().all()) {
            if (s.isEmbeddings()) {
                byId.put(s.getId(), s);
            }
        }
        for (ModelSpec s : OVERRIDES.values()) {
            if (s.isEmbeddings()) {
                byId.put(s.getId(), s);
            }
        }
        return byId.values();
    }

    /** The decision candidate pool: every decision-family spec in the catalog. */
    public static Collection<ModelSpec> decisionPool() {
        Map<String, ModelSpec> byId = new LinkedHashMap<>();
        for (ModelSpec s : backend().all()) {
            if (s.isDecision()) {
                byId.put(s.getId(), s);
            }
        }
        for (ModelSpec s : OVERRIDES.values()) {
            if (s.isDecision()) {
                byId.put(s.getId(), s);
            }
        }
        return byId.values();
    }

    private static ModelsBackend backend() {
        ModelsBackend b = backend;
        if (b == null) {
            synchronized (Models.class) {
                b = backend;
                if (b == null) {
                    b = Reflection.newInstance(Settings.get(ModelSettings.class).backendClass);
                    backend = b;
                }
            }
        }
        return b;
    }

    /**
     * Adopts the deployment's catalog as it stands NOW: the backend, the resolution cache and
     * the learned overrides are dropped, so the next lookup re-resolves through
     * {@link ModelSettings#backendClass} and the deployment's layers. For the moment a
     * discovery has just rewritten {@code models.json} under a running process: the file
     * carries the account's limits fresher than any learned header, so the overrides go with
     * the old catalog deliberately. A rate limiter already built for a model id keeps its
     * running level and adopts changed limits on the next {@link #updateSpec}; a model new to
     * the catalog gets its limiter from the new spec on first use.
     */
    public static void reload() {
        CACHE.clear();
        OVERRIDES.clear();
        backend = null;
    }

    /** Test hook: {@link #reload()} under the name every facade's test reset carries. Package-private. */
    static void resetAll() {
        reload();
    }
}
