/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import ai.redouble.nucleo.tools.registry.*;

import java.util.*;

/**
 * Grants from configuration: a map keyed by a consumer's name or a group name, each naming the
 * tools that key admits. A consumer may use the union of what its own name and each of its groups
 * grant; a consumer no key names may use nothing.
 *
 * <p>This is the simple end of the policy contract, for a host with no identity system of its own
 * to consult - a proof of concept, a single-purpose server, a test. A host built on a platform that
 * already models who may do what implements the same interface over that instead, and gets
 * management surfaces and audit for free rather than maintaining a second list.
 *
 * <p>{@link #requireResolvable} belongs here rather than in each host: a grant naming a tool the
 * catalog does not carry is a configuration error every host makes the same way, and it should
 * fail a boot rather than silently grant nothing.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
public class StaticGrantsAccessPolicy implements McpAccessPolicy {
    private final Map<String, List<String>> grants;

    /**
     * @param grants tools by consumer name or group name; never null, and read as given
     */
    public StaticGrantsAccessPolicy(final Map<String, List<String>> grants) {
        if (grants == null) {
            throw new IllegalArgumentException("Grants are required; an empty map grants nothing to anybody");
        }
        this.grants = grants;
    }

    @Override
    public boolean admits(final McpConsumer consumer, final ToolProvider provider) {
        if (granted(consumer.name(), provider)) {
            return true;
        }
        for (String group : consumer.groups()) {
            if (granted(group, provider)) {
                return true;
            }
        }
        return false;
    }

    private boolean granted(final String key, final ToolProvider provider) {
        List<String> tools = grants.get(key);
        return tools != null && tools.contains(provider.name());
    }

    /**
     * Refuses at startup any grant naming a tool this catalog does not carry - a typo or a stale
     * name, which would otherwise present as a tool that inexplicably cannot be called.
     *
     * @throws IllegalStateException naming the key and the tool that does not resolve
     */
    public void requireResolvable(final McpToolCatalog catalog) {
        for (Map.Entry<String, List<String>> grant : grants.entrySet()) {
            for (String toolName : grant.getValue()) {
                if (catalog.byName(toolName) == null) {
                    throw new IllegalStateException("Grant for " + grant.getKey() + " names " + toolName
                            + ", which is not an exposable tool in this catalog");
                }
            }
        }
    }

    /** The keys this policy grants by, for a startup log line. */
    public Set<String> grantedKeys() {
        return grants.keySet();
    }
}
