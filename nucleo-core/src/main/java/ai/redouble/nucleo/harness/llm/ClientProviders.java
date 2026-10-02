/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.secrets.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Registry of {@link ClientProvider}s, discovered through the JDK {@link ServiceLoader} and
 * keyed by {@link ClientProvider#key()}. A provider module declares its concrete providers in
 * {@code META-INF/services/ai.redouble.nucleo.harness.llm.ClientProvider}; adding a provider is
 * a drop-in class plus its service line, with no central edit. Service discovery finds the same
 * set on the JVM and in a GraalVM native image, and costs nothing at every startup the way a
 * classpath scan does.
 *
 * <p>Providers are instantiated eagerly at scan time, which is cheap and secret-free - the
 * secret-reading SDK client is built only when {@link ClientProvider#createClient(ModelSpec)}
 * runs, so a deployment that never references a provider never constructs its client and
 * never needs its credentials.
 *
 * <p>Clients are never constructed by callers: {@link #llmClient(ModelSpec)} /
 * {@link #embeddingsClient(ModelSpec)} / {@link #decisionClient(ModelSpec)} return the bare, app-shared client per spec id,
 * built once inside the cache's compute and closed together by {@link #shutdownClients()}.
 * The per-job {@code ObservableLLMClient} wrap lives in {@code JobResources}, not here.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public final class ClientProviders {
    private static final Logger log = LoggerFactory.getLogger(ClientProviders.class);
    private static final Map<String, ClientProvider<?>> PROVIDERS = new ConcurrentHashMap<>();
    private static final Map<String, Client> CLIENTS = new ConcurrentHashMap<>();
    private static final AtomicBoolean LOADED = new AtomicBoolean();

    private ClientProviders() {}

    /** App-shared LLM client for the spec. Fails loud if the spec's provider is not an LLM provider. */
    public static LLMClient llmClient(ModelSpec spec) {
        Client client = sharedClient(spec);
        if (!(client instanceof LLMClient llm)) {
            throw new UncorrectableRuntimeLLMException("Provider '" + spec.getProviderKey()
                    + "' for model '" + spec.getId() + "' does not produce an LLM client");
        }
        return llm;
    }

    /** App-shared embeddings client for the spec. Fails loud if the spec's provider is not an embeddings provider. */
    public static EmbeddingsClient embeddingsClient(ModelSpec spec) {
        Client client = sharedClient(spec);
        if (!(client instanceof EmbeddingsClient embeddings)) {
            throw new UncorrectableRuntimeLLMException("Provider '" + spec.getProviderKey()
                    + "' for model '" + spec.getId() + "' does not produce an embeddings client");
        }
        return embeddings;
    }

    /** App-shared decision client for the spec. Fails loud if the spec's provider is not a decision provider. */
    public static DecisionClient decisionClient(ModelSpec spec) {
        Client client = sharedClient(spec);
        if (!(client instanceof DecisionClient decision)) {
            throw new UncorrectableRuntimeLLMException("Provider '" + spec.getProviderKey()
                    + "' for model '" + spec.getId() + "' does not produce a decision client");
        }
        return decision;
    }

    private static Client sharedClient(ModelSpec spec) {
        return CLIENTS.computeIfAbsent(spec.getId(),
                k -> get(spec.getProviderKey()).createClient(spec));
    }

    /**
     * Closes and forgets one provider's shared clients, for a host that replaces the
     * provider's credential at runtime (a corrected session credential): the next call
     * constructs its client around the store's current value instead of serving the one
     * cached around the old.
     */
    public static void evict(String providerKey) {
        CLIENTS.entrySet().removeIf(e -> {
            if (!providerKey.equals(e.getValue().getModel().getProviderKey())) {
                return false;
            }
            if (e.getValue() instanceof LLMClient llm) {
                try {
                    llm.close();
                }
                catch (Throwable t) {
                    log.error("Error closing evicted client {}", e.getKey(), t);
                }
            }
            return true;
        });
    }

    /** Closes all shared clients and clears the cache. Called during application shutdown. */
    public static void shutdownClients() {
        log.info("Shutting down {} shared clients", CLIENTS.size());
        for (Client client : CLIENTS.values()) {
            if (client instanceof LLMClient llm) {
                try {
                    llm.close();
                }
                catch (Throwable t) {
                    log.error("Error closing shared client {}", client.getModel().getId(), t);
                }
            }
        }
        CLIENTS.clear();
    }

    /**
     * Resolves a provider key to its provider. Throws if no provider declares that key -
     * a catalog/config error, never an LLM-correctable one.
     */
    public static ClientProvider<?> get(String providerKey) {
        ClientProvider<?> provider = find(providerKey);
        if (provider == null) {
            throw new UncorrectableRuntimeLLMException("No ClientProvider registered for key: " + providerKey);
        }
        return provider;
    }

    /**
     * The provider for a key, or null when no provider artifact on the classpath declares it -
     * for a caller asking whether a catalog entry can be served at all, where absence is an
     * answer rather than an error.
     */
    public static ClientProvider<?> find(String providerKey) {
        loadOnce();
        return PROVIDERS.get(providerKey);
    }

    /** Every registered provider, keyed by {@link ClientProvider#key()}, for a status surface. */
    public static Map<String, ClientProvider<?>> all() {
        loadOnce();
        return Collections.unmodifiableMap(PROVIDERS);
    }

    /**
     * The concrete spec class a catalog {@code spec_type} names, found among the discovered
     * providers ({@link ClientProvider#specTypes()}); null when no provider on the classpath
     * declares it, which the catalog loader reports as the missing provider artifact.
     */
    public static Class<? extends AbstractModelSpec> specClass(String specType) {
        loadOnce();
        for (ClientProvider<?> provider : PROVIDERS.values()) {
            Class<? extends AbstractModelSpec> declared = provider.specTypes().get(specType);
            if (declared != null) {
                return declared;
            }
        }
        return null;
    }

    private static void loadOnce() {
        // Double-checked once: the flag flips only AFTER discovery completes, and a
        // concurrent first caller blocks on the monitor until it has - two jobs
        // touching their first provider simultaneously (a chat's first LLM call and
        // its conversation-creation enrichment) must both see the full registry,
        // never an empty one mid-load.
        if (!LOADED.get()) {
            synchronized (ClientProviders.class) {
                if (!LOADED.get()) {
                    load();
                    LOADED.set(true);
                }
            }
        }
    }

    static void load() {
        for (ClientProvider<?> provider : ServiceLoader.load(ClientProvider.class)) {
            ClientProvider<?> existing = PROVIDERS.putIfAbsent(provider.key(), provider);
            if (existing != null && existing.getClass() != provider.getClass()) {
                throw new UncorrectableRuntimeLLMException("Duplicate ClientProvider key '" + provider.key()
                        + "' declared by " + provider.getClass().getName() + " and " + existing.getClass().getName());
            }
            // the provider's word on what its credentials are made of, on record before any
            // store is asked for them
            provider.credentialShapes().forEach(CredentialShapes::declare);
        }
        log.info("Registered {} client providers: {}", PROVIDERS.size(), PROVIDERS.keySet());
    }

    /** Test hook: clears the registry and the shared clients so the next access reloads. Package-private. */
    static void reset() {
        synchronized (ClientProviders.class) {
            PROVIDERS.clear();
            CLIENTS.clear();
            LOADED.set(false);
        }
    }
}
