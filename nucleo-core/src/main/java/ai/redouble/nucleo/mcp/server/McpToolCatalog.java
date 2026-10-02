/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import ai.redouble.nucleo.mcp.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.registry.*;
import org.reflections.*;
import org.reflections.scanners.*;
import org.reflections.util.*;
import org.slf4j.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * The exposable set: every {@link Tool} carrying {@link MCP} under the scanned package
 * prefixes, plus the providers handed in explicitly, keyed by tool name.
 * <p>
 * Exposable means publishable, so the catalog refuses at construction anything it could
 * not serve: an {@link MCP} class without {@link ToolName}, two different classes
 * claiming one name, and a provider whose input schema could not be generated
 * ({@link ClassToolProvider} reports that as a null {@code schemaJson}). The same class
 * arriving through a scan and explicitly is one entry: providers dedupe by
 * {@link ClassToolProvider#equals}, which is class identity.
 * <p>
 * A lookup miss is data, never an error: the serving layer answers an unknown name the
 * same way it answers an ungranted one.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
public final class McpToolCatalog {
    private static final Logger log = LoggerFactory.getLogger(McpToolCatalog.class);
    private final Map<String, ToolProvider> byName;

    public McpToolCatalog(List<String> scanPackages, List<ToolProvider> explicit) {
        Map<String, ToolProvider> collected = new LinkedHashMap<>();
        for (String pkg : scanPackages) {
            for (ToolProvider provider : scan(pkg)) {
                admit(collected, provider);
            }
        }
        for (ToolProvider provider : explicit) {
            admit(collected, provider);
        }
        this.byName = Collections.unmodifiableMap(collected);
    }

    /**
     * The provider registered under a tool name, or null when no exposable tool carries it.
     */
    public ToolProvider byName(String name) {
        return byName.get(name);
    }

    public Collection<ToolProvider> all() {
        return byName.values();
    }

    private static void admit(Map<String, ToolProvider> collected, ToolProvider provider) {
        if (provider.schemaJson() == null) {
            throw new IllegalStateException("Tool " + provider.name() + " (" + provider.toolClass().getName()
                    + ") is exposable but its input schema could not be generated; exposable means publishable");
        }
        ToolProvider existing = collected.get(provider.name());
        if (existing == null) {
            collected.put(provider.name(), provider);
            return;
        }
        if (!existing.equals(provider)) {
            throw new IllegalStateException("Tool name collision in the MCP catalog: " + provider.name()
                    + " is claimed by " + existing.toolClass().getName() + " and " + provider.toolClass().getName());
        }
    }

    @SuppressWarnings("rawtypes")
    private static List<ToolProvider> scan(String pkg) {
        Reflections reflections = new Reflections(new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage(pkg))
                .setScanners(Scanners.SubTypes));
        List<ToolProvider> result = new ArrayList<>();
        for (Class<? extends Tool> cls : reflections.getSubTypesOf(Tool.class)) {
            if (!cls.getName().startsWith(pkg + ".") || !cls.isAnnotationPresent(MCP.class)) {
                continue;
            }
            if (Modifier.isAbstract(cls.getModifiers()) || cls.isInterface()) {
                throw new IllegalStateException("@MCP on " + cls.getName() + " which is not a concrete tool class");
            }
            if (!cls.isAnnotationPresent(ToolName.class)) {
                throw new IllegalStateException("@MCP on " + cls.getName() + " without @ToolName: an exposable tool must be nameable");
            }
            result.add(ClassToolProvider.of(cls));
        }
        log.info("McpToolCatalog: scanned package {} -> {} exposable tools", pkg, result.size());
        return result;
    }
}
