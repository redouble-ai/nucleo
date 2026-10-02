/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import java.lang.annotation.*;

/**
 * Declares the computational weight of a tool on a normalized 0-100 scale.
 * This metadata is appended to tool descriptions so the LLM can make
 * proportional effort decisions (e.g., skip heavy subagents for trivial questions).
 *
 * <p>For thinkers, {@link #level()} indicates the depth of the subagent hierarchy
 * beneath this tool (0 = leaf tools only, 1 = has L0 sub-thinkers, etc.).
 * This scales to arbitrary nesting depth.
 *
 * <p>When absent, auto-detection applies:
 * <ul>
 *   <li>Thinker subclasses default to THINKER level=0, min=20, max=80</li>
 *   <li>Everything else defaults to API_CALL, min=10, max=10</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-21)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolWeight {
    ToolType type();
    /** Depth of subagent hierarchy beneath this tool. Only meaningful for THINKER type. */
    int level() default 0;
    int min() default 0;
    int max() default 0;
}
