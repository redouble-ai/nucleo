/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt.skill;

import ai.redouble.nucleo.prompt.*;

import java.util.*;

/**
 * Structured bundle of prompt content admitted into a thinker's conversation as a unit.
 *
 * <p>A Skill comprises a name, a short description (what/when to use), a body (the
 * instructions themselves), a named collection of resources (sub-prompts referenced by
 * the body, e.g. examples, worked solutions, checklists), a list of tool names the skill
 * expects to exist in the thinker's registry, and provenance metadata.
 *
 * <p>Each contained {@link Prompt} is a first-class framework object: guardrailed,
 * hashable, substitutable. The Skill layer composes them; no implementation-level raw
 * strings escape this abstraction.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
public interface Skill {
    /** Canonical name; unique across the SkillRegistry. Pattern: {@code <domain>.<skill-name>}. */
    String name();

    /** Short "what/when to use" summary. Matches Anthropic's Skills {@code description}. */
    Prompt description();

    /** Instructional body. The main text the LLM reads when the skill is admitted. */
    Prompt body();

    /** Named sub-prompts referenced from the body (examples, checklists, worked solutions). */
    Map<String, Prompt> resources();

    /** Tool names the skill expects to have available. Unresolved names surface as a warning. */
    List<String> suggestedTools();

    /** Provenance metadata (author, origin, trigger keyword, etc.) and content lineage. */
    SkillMetadata metadata();

    /**
     * Body-only rendering used wherever an admitted skill appears inside a message's content
     * (the per-message {@code SkillBlock} path): {@code [Skill: <name>]} header followed by the
     * instructional body. This is the canonical in-conversation skill text; every model-facing
     * rendering routes through here or {@link #renderForSystemPrompt}, never a client's own
     * concatenation (the header-only {@code [Skill: <name>]} placeholder in
     * {@code OutgoingMessage.getRawContent} is log/estimate text, not model input).
     */
    static String renderBody(Skill skill) {
        return "[Skill: " + skill.name() + "]\n" + bodyText(skill);
    }

    /**
     * System-prefix rendering used when a skill is laid into the stable system context: the
     * {@code [Skill: <name>]} header, the short description (only when present, separated by a
     * blank line), then the body. Byte-for-byte the shape the provider system-block loops emit,
     * so it must stay stable - it sits inside the cached prefix and any drift invalidates the
     * cache.
     */
    static String renderForSystemPrompt(Skill skill) {
        String desc = descriptionText(skill);
        StringBuilder s = new StringBuilder("[Skill: ").append(skill.name()).append("]\n");
        if (!desc.isEmpty()) {
            s.append(desc).append("\n\n");
        }
        s.append(bodyText(skill));
        return s.toString();
    }

    private static String bodyText(Skill skill) {
        return skill.body() != null && skill.body().content() != null ? skill.body().content().asText() : "";
    }

    private static String descriptionText(Skill skill) {
        return skill.description() != null && skill.description().content() != null ? skill.description().content().asText() : "";
    }
}
