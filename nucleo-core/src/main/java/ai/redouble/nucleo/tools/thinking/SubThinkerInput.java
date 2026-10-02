/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Input for a dynamically spawned sub-agent.
 *
 * <p>The parent thinker provides an objective via the inherited {@code query} field.
 * The objective can contain artifact references which are resolved through the
 * normal artifact serialization pipeline.
 *
 * <p>Tools are inherited from the parent thinker automatically - no tool selection needed.
 *
 * <p>Use {@code exclusions} to prevent convergence when spawning multiple sub-agents
 * over the same corpus. Each exclusion describes a topic, angle, or finding that
 * another sub-agent is already covering, so this one should avoid it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-29)
 */
public class SubThinkerInput extends ThinkerInput {
    @LLMDescription("Topics, angles, or findings that other sub-agents are already covering. "
                   + "Do NOT investigate or repeat these areas - focus on what is NOT listed here.")
    private List<String> exclusions;

    public List<String> getExclusions() {
        return exclusions;
    }

    public void setExclusions(List<String> exclusions) {
        this.exclusions = exclusions;
    }
}
