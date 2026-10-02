/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.slf4j.*;

import java.util.*;

/**
 * The rules behind {@link AbstractThinker#isForceReadOnly()}: the sweep that removes mutating
 * tools from a registry, and the admission assertion that backs it. They apply to a thinker
 * bound at construction and to one that inherited the binding from the flow that submitted
 * it - the palette does not care where the binding came from.
 *
 * <p>The two are not redundant. {@link #sweep} decides what the model is offered;
 * {@link #requireReadOnly} decides what it is allowed to run. Only the second is a
 * guarantee, because the registry is rebuilt every turn and can be widened after the
 * definitions were built - by {@code request_tools}, by {@code ToolHub} system-wide tools,
 * or by a direct {@code addTool}. Both are called from {@code AbstractThinker}'s own call
 * path rather than from an overridable hook, so no subclass can drop either by failing to
 * chain {@code super}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-06)
 */
public final class ReadOnlyPalette {
    private static final Logger log = LoggerFactory.getLogger(ReadOnlyPalette.class);

    private ReadOnlyPalette() {
    }

    /**
     * Removes every mutating provider from the registry, naming each removal.
     *
     * <p>Idempotent, as the reconcile-hook contract requires: it derives the registry
     * contents from the providers present, so running it twice on the same state produces
     * the same result.
     *
     * <p>Removals are logged rather than silent. A read-only thinker is routinely handed a
     * superset - the whole toolbox of the doer's children - and subtraction is the point,
     * but a palette that quietly shrinks is indistinguishable from one that was never
     * populated, and that difference matters when a skeptic reports it could not verify
     * something. Filtering is the right response even for tools the thinker's own class
     * declared: the binding can be applied from outside, so a declaration is not by itself
     * a claim that the tool is read-only.
     */
    public static void sweep(ToolRegistry registry, String owner) {
        List<ToolProvider> mutating = new ArrayList<>();
        for (ToolProvider provider : registry.getAllProviders()) {
            if (!provider.readOnly()) {
                mutating.add(provider);
            }
        }
        for (ToolProvider provider : mutating) {
            registry.unregister(provider.name());
            log.info("{} is read-only: withheld tool {}", owner, provider.name());
        }
    }

    /**
     * Asserts a tool may run under a read-only thinker.
     *
     * <p>A missing provider is a violation, not an absence: the dispatcher is about to
     * resolve this name against a registry, and a name this check cannot evaluate is a name
     * whose read-only status is unknown.
     *
     * @throws GuardrailException always correctable - the model picked an unavailable tool
     *     and can pick another
     */
    public static void requireReadOnly(ToolRegistry registry, String toolName, String owner)
            throws GuardrailException {
        ToolProvider provider = registry.getProviderByName(toolName);
        if (provider == null) {
            throw new GuardrailException(owner + " is read-only and tool '" + toolName
                    + "' is not in its palette. Use one of the tools you were given.");
        }
        if (!provider.readOnly()) {
            throw new GuardrailException(owner + " is read-only and may only call tools that "
                    + "change nothing. Tool '" + toolName + "' is not one of them. "
                    + "Observe and report instead of acting.");
        }
    }

}
