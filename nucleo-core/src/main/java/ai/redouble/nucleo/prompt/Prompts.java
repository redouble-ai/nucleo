/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.prompt.sources.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Governor-style facade for the Prompt registry. Only public entry point for obtaining
 * {@link Prompt} instances.
 *
 * <p>Resolution order in {@link #produce(String)}:
 * <ol>
 *   <li>CACHE (for keys served by {@link StaticPromptSource}) - returned immediately with
 *       fingerprint-aware guardrail re-validation if new guardrails were registered since cache insert.</li>
 *   <li>OVERRIDES.get(key) - per-key override set via {@link #replace(String, PromptSource)}.</li>
 *   <li>GLOBAL_BACKEND - wholesale substitute set via {@link #setGlobalBackend(PromptSource)}.</li>
 *   <li>DEFAULTS.get(key) - code-declared default populated by the {@code @StaticPrompt} /
 *       {@code @DynamicPrompt} scanner.</li>
 * </ol>
 * A miss in every tier throws {@link PromptNotFoundException}.
 *
 * <p>Invalidation ordering is reverse of intuitive put/remove so concurrent readers never
 * observe the race "new override, stale cache":
 * <ul>
 *   <li>{@link #replace}: {@code CACHE.remove(key); OVERRIDES.put(key, src);}</li>
 *   <li>{@link #resetOverride}: {@code CACHE.remove(key); OVERRIDES.remove(key);}</li>
 *   <li>{@link #setGlobalBackend}: {@code CACHE.clear(); GLOBAL_BACKEND = src;}</li>
 * </ul>
 * The produce side holds up the same guarantee from its end: after publishing a static
 * source's prompt to the cache, it re-resolves the key and evicts its own entry when a
 * substitution landed mid-produce, so a stale prompt can never shadow a fresh source.
 *
 * <p>Guardrails attach per-key via {@link #addGuardrail}. They are NOT attached to the
 * source, so source substitution cannot bypass validation. Baseline guardrails
 * ({@link #addBaselineGuardrail}) apply to every Prompt produced regardless of key,
 * including inline {@link #of} calls; nothing is registered by default - a deployment
 * wires the shipped ones (size cap, exfiltration marker) itself.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-20)
 */
public final class Prompts {
    private Prompts() {
    }

    private static final Map<String, PromptSource> DEFAULTS = new ConcurrentHashMap<>();
    private static final Map<String, PromptSource> OVERRIDES = new ConcurrentHashMap<>();
    private static volatile PromptSource GLOBAL_BACKEND;
    private static final Map<String, List<Supplier<Guardrail<Prompt>>>> GUARDRAILS = new ConcurrentHashMap<>();
    private static final Map<String, Prompt> CACHE = new ConcurrentHashMap<>();
    private static final Map<ValidationKey, ValidationResult> GUARDRAIL_CACHE = new ConcurrentHashMap<>();
    private static final List<Supplier<Guardrail<Prompt>>> BASELINE = new CopyOnWriteArrayList<>();
    private static final Map<Prompt, PromptContext> CONTEXT_CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private static final AtomicBoolean SCAN_TRIGGERED = new AtomicBoolean();
    private static volatile String defaultScanPackage = "ai.redouble";

    // ========================= Production =========================

    /**
     * Resolve the registered source for this key, produce its content, validate via per-key
     * and baseline guardrails, return a {@link Prompt}. If the source is a
     * {@link StaticPromptSource}, subsequent calls return a cached Prompt.
     *
     * @throws PromptNotFoundException if no source resolves for the key
     * @throws GuardrailException if any guardrail rejects the produced Prompt
     */
    public static Prompt produce(String key) throws PromptNotFoundException, GuardrailException {
        triggerScanOnce();
        Prompt cached = CACHE.get(key);
        if (cached != null) {
            runGuardrailsCached(key, cached);
            return cached;
        }
        PromptSource source = resolveSource(key);
        if (source == null) {
            throw new PromptNotFoundException(key);
        }
        JsonNode content = source.produce(key);
        Prompt prompt = buildPrompt(key, content);
        runGuardrailsCached(key, prompt);
        if (source instanceof StaticPromptSource) {
            CACHE.putIfAbsent(key, prompt);
            // A substitution that landed while this produce was in flight already ran its
            // CACHE.remove - BEFORE the line above published the now-stale prompt. Re-resolve
            // and evict on divergence, or the stale entry shadows the new source forever.
            if (resolveSource(key) != source) {
                CACHE.remove(key);
            }
        }
        return prompt;
    }

    /**
     * Build a one-shot Prompt under a synthetic key derived from a content hash. Not
     * registered. Not substitutable. For inline throwaway content; registered
     * {@code @StaticPrompt} / {@code @DynamicPrompt} declarations are the preferred path
     * for anything reusable.
     * Baseline guardrails still run.
     */
    public static Prompt of(String text) throws GuardrailException {
        JsonNode content = TextNode.valueOf(text);
        String inlineKey = "inline:" + hash("", content);
        Prompt prompt = buildPrompt(inlineKey, content);
        runGuardrailsCached(inlineKey, prompt);
        return prompt;
    }

    /**
     * Register an inline text prompt as a code-level default under an explicit key and
     * return it. If the key already has a default, the prior registration is replaced
     * (this is the ergonomic entry point for "declare a prompt in code without the
     * annotation scanner," so last-write-wins is the documented semantic). Does not
     * populate the override layer - {@link #replace} and {@link #resetOverride} still
     * function normally on top.
     */
    public static Prompt of(String key, String text) throws PromptNotFoundException, GuardrailException {
        DEFAULTS.put(key, new StaticTextSource(text));
        CACHE.remove(key);
        return produce(key);
    }

    /**
     * Lazy bind: if no default is registered for this key, install one wrapping {@code text}
     * in a {@link StaticTextSource}. Subsequent calls reuse the existing registration and
     * preserve the cached Prompt - no put, no cache invalidate. The intended caller is the
     * runtime hook on {@code SingleObjectiveThinker} that resolves a {@code @StaticPrompt}
     * instance-method declaration on first construction; the hook needs idempotent
     * registration semantics to avoid invalidating the StaticPromptSource cache on every
     * thinker construction.
     */
    public static Prompt bindStaticDefault(String key, String text) throws PromptNotFoundException, GuardrailException {
        DEFAULTS.computeIfAbsent(key, k -> new StaticTextSource(text));
        return produce(key);
    }

    // ========================= Registration =========================

    /**
     * Scanner-only registration of a code-declared default. Apps never call this directly;
     * they declare {@code @StaticPrompt} / {@code @DynamicPrompt} members and the scanner
     * populates defaults.
     *
     * @throws PromptRegistrationException on duplicate key
     */
    static void registerDefault(String key, PromptSource source) {
        PromptSource existing = DEFAULTS.putIfAbsent(key, source);
        if (existing != null) {
            throw new PromptRegistrationException("Duplicate prompt default for key: " + key);
        }
    }

    /** Per-key override. Invalidates the cache entry for this key before publishing the new source. */
    public static void replace(String key, PromptSource source) {
        CACHE.remove(key);
        OVERRIDES.put(key, source);
    }

    /** Clear a per-key override, returning produce() to the global-backend / default layer. */
    public static void resetOverride(String key) {
        CACHE.remove(key);
        OVERRIDES.remove(key);
    }

    /** Substitute a single source for every key. Clears the cache so every key re-produces. */
    public static void setGlobalBackend(PromptSource backend) {
        CACHE.clear();
        GLOBAL_BACKEND = backend;
    }

    public static void clearGlobalBackend() {
        CACHE.clear();
        GLOBAL_BACKEND = null;
    }

    /**
     * Attach a guardrail factory to a specific key. Survives source replacement. Each
     * {@code produce(key)} call that needs validation invokes the factory to get a fresh
     * guardrail {@link Job} instance and dispatches it via {@link JobDispatcher} - Jobs
     * are one-shot, so factories (not instances) are what survive in the registry.
     * Invalidates the cached validation result.
     */
    public static void addGuardrail(String key, Supplier<Guardrail<Prompt>> factory) {
        GUARDRAILS.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(factory);
        GUARDRAIL_CACHE.clear();
    }

    /**
     * Attach a framework-baseline guardrail factory that runs for every Prompt produced.
     * Factory is invoked fresh per validation (see {@link #addGuardrail}). Order of
     * registration determines execution order.
     */
    public static void addBaselineGuardrail(Supplier<Guardrail<Prompt>> factory) {
        BASELINE.add(factory);
        GUARDRAIL_CACHE.clear();
    }

    /** Remove a previously-registered baseline guardrail factory. No-op if not present. */
    public static void removeBaselineGuardrail(Supplier<Guardrail<Prompt>> factory) {
        if (BASELINE.remove(factory)) {
            GUARDRAIL_CACHE.clear();
        }
    }

    /** Remove all registrations, caches, guardrails, and the global backend. Tests only. */
    static void resetAll() {
        DEFAULTS.clear();
        OVERRIDES.clear();
        GLOBAL_BACKEND = null;
        GUARDRAILS.clear();
        CACHE.clear();
        GUARDRAIL_CACHE.clear();
        BASELINE.clear();
        CONTEXT_CACHE.clear();
        SCAN_TRIGGERED.set(false);
    }

    // ========================= Scanner integration =========================

    /**
     * Eagerly scan a package for {@code @StaticPrompt} / {@code @DynamicPrompt} declarations
     * and register their default sources. Also disables the first-produce auto-scan since
     * the caller has explicitly directed discovery. One scan per process: if any scan has
     * already fired (this method or the auto-scan), the call is a silent no-op.
     */
    public static void scanPackage(String pkg) {
        if (SCAN_TRIGGERED.compareAndSet(false, true)) {
            PromptScanner.scan(pkg);
        }
    }

    private static void triggerScanOnce() {
        if (SCAN_TRIGGERED.compareAndSet(false, true)) {
            PromptScanner.scan(defaultScanPackage);
        }
    }

    /**
     * Registers one {@code @StaticPrompt}/{@code @DynamicPrompt} element (a type, static field or
     * static method) discovered at build time, and marks discovery done so the first-produce
     * auto-scan never fires. A build that enumerates the annotated elements itself - the Quarkus
     * extension does, from its class index - calls this per element instead of the classpath scan
     * that a native image cannot run. The per-element validation and registration are
     * {@link PromptScanner}'s, so build-time and scan discovery share one contract.
     */
    public static void registerAnnotated(java.lang.reflect.AnnotatedElement element) {
        SCAN_TRIGGERED.set(true);
        switch (element) {
            case Class<?> type -> PromptScanner.registerType(type);
            case java.lang.reflect.Field field -> PromptScanner.registerField(field);
            case java.lang.reflect.Method method -> PromptScanner.registerMethod(method);
            default -> throw new PromptRegistrationException("A prompt annotation can only mark a type, field or method, not " + element);
        }
    }

    /**
     * Change the package auto-scanned on first {@link #produce} or {@link #of} call. Must
     * be called before any produce/of, otherwise has no effect because the auto-scan has
     * already fired.
     */
    public static void setDefaultScanPackage(String pkg) {
        defaultScanPackage = pkg;
    }

    /**
     * Eagerly produce every registered {@link StaticPromptSource} default, running its
     * guardrails and populating the cache. Call at bootstrap (outside any resource-holding
     * region) so subsequent thinker-side {@link #produce} calls hit the cache.
     */
    public static void warmCache() throws PromptNotFoundException, GuardrailException {
        for (Map.Entry<String, PromptSource> e : DEFAULTS.entrySet()) {
            if (e.getValue() instanceof StaticPromptSource) {
                produce(e.getKey());
            }
        }
    }

    // ========================= Internal - construction and resolution =========================

    /**
     * The ONE canonical hashing and Prompt construction site. All produce/of paths route
     * through here. Package-private because direct construction outside the facade is
     * forbidden (cheats the cache, skips guardrails).
     */
    static Prompt buildPrompt(String key, JsonNode content) {
        return new TextPrompt(key, content == null ? NullNode.getInstance() : content);
    }

    /**
     * Return the {@link PromptContext} lineage for a given (key, content) pair. Called by
     * {@link TextPrompt#context()}. Memoized on the Prompt identity via a weak map so
     * repeated {@code context()} calls on the same Prompt don't rehash.
     */
    public static PromptContext computeContextFor(String key, JsonNode content) {
        Prompt probe = new TextPrompt(key, content);
        PromptContext cached = CONTEXT_CACHE.get(probe);
        if (cached != null) {
            return cached;
        }
        PromptContext ctx = new PromptContext();
        ctx.setContentHash(hash(key, content));
        ctx.setProducedAt(Instant.now());
        CONTEXT_CACHE.put(probe, ctx);
        return ctx;
    }

    private static PromptSource resolveSource(String key) {
        PromptSource s = OVERRIDES.get(key);
        if (s != null) {
            return s;
        }
        PromptSource backend = GLOBAL_BACKEND;
        if (backend != null) {
            return backend;
        }
        return DEFAULTS.get(key);
    }

    private static void runGuardrailsCached(String key, Prompt prompt) throws GuardrailException {
        List<Supplier<Guardrail<Prompt>>> perKey = GUARDRAILS.getOrDefault(key, Collections.emptyList());
        List<Supplier<Guardrail<Prompt>>> all = new ArrayList<>(BASELINE.size() + perKey.size());
        all.addAll(BASELINE);
        all.addAll(perKey);
        if (all.isEmpty()) {
            return;
        }
        List<Guardrail<Prompt>> instances = new ArrayList<>(all.size());
        for (Supplier<Guardrail<Prompt>> factory : all) {
            instances.add(factory.get());
        }
        String fingerprint = fingerprint(instances);
        String contentHash = prompt.context().getContentHash();
        ValidationKey vkey = new ValidationKey(contentHash, fingerprint);
        ValidationResult result = GUARDRAIL_CACHE.computeIfAbsent(vkey, k -> dispatchAll(instances, prompt));
        if (!result.passed()) {
            throw result.failure();
        }
    }

    private static ValidationResult dispatchAll(List<Guardrail<Prompt>> guardrails, Prompt prompt) {
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        for (Guardrail<Prompt> g : guardrails) {
            g.setTarget(prompt);
            try {
                dispatcher.submit(g).get();
            }
            catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof GuardrailException ge) {
                    return new ValidationResult(false, ge);
                }
                return new ValidationResult(false, new GuardrailException(
                    "Guardrail " + g.getClass().getSimpleName() + " failed: " + cause.getMessage()));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ValidationResult(false, new GuardrailException(
                    "Guardrail " + g.getClass().getSimpleName() + " was interrupted"));
            }
        }
        return new ValidationResult(true, null);
    }

    private static String fingerprint(List<Guardrail<Prompt>> guardrails) {
        StringBuilder sb = new StringBuilder();
        for (Guardrail<Prompt> g : guardrails) {
            sb.append(g.getClass().getName()).append(';');
        }
        return sha256(sb.toString());
    }

    private static String hash(String key, JsonNode content) {
        String payload = key + "\n" + NucleoJsonSerializer.write(content == null ? NullNode.getInstance() : content);
        return sha256(payload);
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ========================= Nested types =========================

    private record ValidationKey(String contentHash, String fingerprint) {
    }

    private record ValidationResult(boolean passed, GuardrailException failure) {
    }
}
