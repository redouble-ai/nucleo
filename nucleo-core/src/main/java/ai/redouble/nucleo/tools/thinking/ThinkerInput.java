/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Base class for all thinker inputs.
 * Every thinker accepts a natural language query as its core input.
 *
 * <p>An input has no rendering of its own. The thinker places it in a {@link ThinkerObjective}
 * beside the registered prompt, and the conversation serializes that object to JSON for the
 * model, so every thinker's input arrives in the same shape: its fields, under their schema
 * names, with {@link LLMContextIgnore} fields left out.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
public abstract class ThinkerInput  {
    @LLMRequired
    @LLMDescription("Natural language query or instruction")
    private String query;

    @LLMDescription("How deep the investigation should go. "
                    + "IMMEDIATE: Answer from your existing knowledge only. Do NOT use any tools. "
                    + "QUICK: Use minimal tools if needed. Do NOT invoke sub-agents. "
                    + "STANDARD: Use tools as needed. This is the right choice for the vast majority of tasks. "
                    + "THOROUGH: Deep investigation with exhaustive coverage. Only when the task explicitly demands it. "
                    + "ULTRA_THOROUGH: Maximum effort, reserved for critical high-stakes analyses only. "
                    + "You must NEVER set a depth higher than what was given to you.")
    private Depth depth = Depth.STANDARD;

    @LLMContextIgnore
    @LLMDescription("Artifact references to hand to this sub-agent, in canonical «artifact:...» form. "
                    + "The sub-agent runs in its own context and can ONLY see the artifacts you list here - "
                    + "so list exactly the inputs it needs (for example the list ref it should process). "
                    + "Referencing an artifact in the query text is not enough; it must be listed here to be conveyed.")
    private List<String> artifactRefs;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Depth getDepth() {
        return depth;
    }

    public void setDepth(Depth depth) {
        this.depth = depth;
    }

    public List<String> getArtifactRefs() {
        return artifactRefs;
    }

    public void setArtifactRefs(List<String> artifactRefs) {
        this.artifactRefs = artifactRefs;
    }
}
