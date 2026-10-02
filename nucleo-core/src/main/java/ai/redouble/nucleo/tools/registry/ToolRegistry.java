/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Registry for {@link ToolProvider}s, keyed by {@link ToolProvider#name()}.
 *
 * <p>Class-based registration ({@link #register(Class)}, {@link #registerAll(Collection)},
 * {@link #unregister(Class)}) is the convenience path for framework-shipped tools: the
 * methods wrap the class in a {@link ClassToolProvider}. Anything carrying runtime wiring
 * (an MCP connector, a dynamic catalog door) registers its provider directly via
 * {@link #register(ToolProvider)}.
 *
 * <p>Duplicate-registration policy: a second registration under an existing name is an
 * <em>upsert</em> when the new provider has the same concrete type and name as the
 * existing entry (e.g. registering the same {@link ClassToolProvider} twice from system-wide
 * + per-thinker overlap is harmless). A registration under an existing name with a
 * <em>different</em> provider type throws {@link IllegalStateException} to surface
 * collisions early.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-22)
 */
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ConcurrentHashMap<String, ToolProvider> providersByName = new ConcurrentHashMap<>();

    /**
     * Returns the names of every registered tool.
     */
    public Set<String> getRegisteredToolNames() {
        return Collections.unmodifiableSet(providersByName.keySet());
    }

    /**
     * Convenience for class-based callers: wraps the class in a {@link ClassToolProvider}
     * and registers it. A class without {@code @ToolName} has no name to register under,
     * so it is skipped with a warning.
     */
    public void register(Class<? extends Tool> toolClass) {
        if (toolClass.getAnnotation(ToolName.class) == null) {
            log.warn("Tool class {} has no @ToolName annotation", toolClass.getSimpleName());
            return;
        }
        register(ClassToolProvider.of(toolClass));
    }

    /**
     * Registers a provider under {@link ToolProvider#name()}. Upserts when an existing
     * entry has the same provider type and name; throws on conflict.
     */
    public void register(ToolProvider provider) {
        String name = provider.name();
        providersByName.compute(name, (key, existing) -> {
            if (existing == null) {
                return provider;
            }
            if (existing.getClass().equals(provider.getClass()) && existing.name().equals(provider.name())) {
                return provider;
            }
            throw new IllegalStateException(
                    "Tool name '" + name + "' is already registered by " + existing.getClass().getSimpleName()
                            + "; cannot replace with a different provider type "
                            + provider.getClass().getSimpleName());
        });
    }

    /**
     * Registers each class in the collection. Class entries with no {@code @ToolName}
     * are skipped with a warning.
     */
    public void registerAll(Collection<Class<? extends Tool>> toolClasses) {
        for (Class<? extends Tool> toolClass : toolClasses) {
            register(toolClass);
        }
    }

    /**
     * Registers each provider in the collection.
     */
    public void registerAllProviders(Collection<? extends ToolProvider> providers) {
        for (ToolProvider provider : providers) {
            register(provider);
        }
    }

    /**
     * Unregisters by name. No-op if the name is not registered.
     */
    public void unregister(String toolName) {
        ToolProvider removed = providersByName.remove(toolName);
        if (removed != null) {
            log.info("Unregistered tool: {}", toolName);
        }
    }

    /**
     * Class-based unregister: derives the name from the class's {@code @ToolName} and
     * delegates to {@link #unregister(String)}.
     */
    public void unregister(Class<? extends Tool> toolClass) {
        ToolName annotation = toolClass.getAnnotation(ToolName.class);
        if (annotation != null) {
            unregister(annotation.value());
        }
    }

    /**
     * Returns every registered provider. Used by thinker tool-definition-block builders
     * and admission scans.
     */
    public Collection<ToolProvider> getAllProviders() {
        return Collections.unmodifiableCollection(providersByName.values());
    }

    /**
     * Looks up a provider by name. Returns null when not registered. A null name is never
     * registered: names arrive from model output, a malformed call can omit one, and the
     * backing map would throw on a null key where this contract says null.
     */
    public ToolProvider getProviderByName(String toolName) {
        return toolName == null ? null : providersByName.get(toolName);
    }

    /**
     * True iff the class's {@code @ToolName} value is currently registered.
     */
    public boolean hasToolClass(Class<? extends Tool> toolClass) {
        ToolName annotation = toolClass.getAnnotation(ToolName.class);
        return annotation != null && providersByName.containsKey(annotation.value());
    }

    /**
     * True iff a provider with the given name is registered.
     */
    public boolean hasTool(String toolName) {
        return providersByName.containsKey(toolName);
    }

    /**
     * Builds the tool instance for the given name via the registered provider's factory.
     *
     * @throws CorrectableRuntimeLLMException when no tool is registered with that name —
     *     LLM-correctable, the model can pick a different name and retry.
     * @throws LLMReadableCheckedException when the provider's factory itself fails (for
     *     example an MCP-bound provider cannot reach the underlying server).
     */
    public Tool<?, ?> createTool(String toolName, Identifiable parent)
            throws LLMReadableCheckedException {
        ToolProvider provider = getProviderByName(toolName);
        if (provider == null) {
            throw new CorrectableRuntimeLLMException(
                    "Unknown tool: " + toolName + ". Available tools: " + providersByName.keySet());
        }
        return provider.create(parent);
    }

    /**
     * Returns the input type for a registered tool by name.
     */
    public Class<?> getInputTypeByToolName(String toolName) {
        ToolProvider provider = getProviderByName(toolName);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Unknown tool: " + toolName + ". Available tools: " + providersByName.keySet());
        }
        return provider.inputType();
    }

    /**
     * Display name for the given tool. Falls back to the tool name when not registered.
     */
    public String getDisplayName(String toolName) {
        ToolProvider provider = getProviderByName(toolName);
        return provider != null ? provider.displayName() : toolName;
    }

    /**
     * Action verb for the given tool. Empty string when not specified.
     */
    public String getActionVerb(String toolName) {
        ToolProvider provider = getProviderByName(toolName);
        return provider != null ? provider.actionVerb() : "";
    }
}
