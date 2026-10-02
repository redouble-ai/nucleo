/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt.skill;

import ai.redouble.nucleo.prompt.*;

import java.util.*;

/**
 * Baseline {@link Skill} implementation. A record whose components are the constituent
 * prompts and metadata; serializes naturally through the existing Prompt serialization
 * machinery (each {@link Prompt} component emits as {@code {key, content}}).
 * Null {@code resources} / {@code suggestedTools} normalize to immutable empties at
 * construction, so consumers never null-check the collections.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
public record TextSkill(
    String name,
    Prompt description,
    Prompt body,
    Map<String, Prompt> resources,
    List<String> suggestedTools,
    SkillMetadata metadata
) implements Skill {
    public TextSkill {
        resources = resources == null ? Map.of() : Map.copyOf(resources);
        suggestedTools = suggestedTools == null ? List.of() : List.copyOf(suggestedTools);
    }
}
