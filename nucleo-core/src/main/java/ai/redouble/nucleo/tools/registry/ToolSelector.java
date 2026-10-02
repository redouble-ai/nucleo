/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;

import java.util.*;

/**
 * Accumulates {@link ToolProvider}s from packages and individual additions.
 *
 * <p>Class-based add methods wrap the class in a {@link ClassToolProvider} so
 * existing call sites compile unchanged. New code can register providers directly
 * via {@link #addProvider(ToolProvider)} or attach an entire MCP connector's
 * provider list via {@link #addProviders(Collection)}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-15)
 */
public class ToolSelector {

    final Set<ToolProvider> providers = new LinkedHashSet<>();

    public ToolSelector() {
    }

    @SafeVarargs
    public ToolSelector(Class<? extends Tool>... clazz) {
        addClass(clazz);
    }

    public ToolSelector(String packageName) {
        addToolPackage(packageName);
    }

    @SafeVarargs
    public final void addClass(Class<? extends Tool>... clazz) {
        for (Class<? extends Tool> c : clazz) {
            providers.add(ClassToolProvider.of(c));
        }
    }

    public void addClasses(Collection<Class<? extends Tool>> clazzes) {
        for (Class<? extends Tool> c : clazzes) {
            providers.add(ClassToolProvider.of(c));
        }
    }

    public void addToolPackage(String packageName) {
        providers.addAll(ToolHub.getInstance().findToolProviders(packageName));
    }

    public void addProvider(ToolProvider provider) {
        providers.add(provider);
    }

    public void addProviders(Collection<? extends ToolProvider> toAdd) {
        providers.addAll(toAdd);
    }

    public Set<ToolProvider> getProviders() {
        return providers;
    }
}
