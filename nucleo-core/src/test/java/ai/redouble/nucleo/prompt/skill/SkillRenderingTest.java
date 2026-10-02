/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt.skill;

import ai.redouble.nucleo.prompt.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two skill render shapes. These strings sit inside the cached system prefix, so their exact form
 * (header, blank-line separator, empty-description handling) must stay stable - drift invalidates the
 * prompt cache.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
class SkillRenderingTest {

    private static Prompt prompt(String text) {
        return new Prompt() {
            public String key() { return "k"; }
            public JsonNode content() { return TextNode.valueOf(text); }
            public PromptContext context() { return null; }
        };
    }

    private static Skill skill(String name, String description, String body) {
        return new Skill() {
            public String name() { return name; }
            public Prompt description() { return description == null ? null : prompt(description); }
            public Prompt body() { return body == null ? null : prompt(body); }
            public Map<String, Prompt> resources() { return Map.of(); }
            public List<String> suggestedTools() { return List.of(); }
            public SkillMetadata metadata() { return null; }
        };
    }

    @Test
    void renderBodyIsHeaderPlusBody() {
        assertEquals("[Skill: doc.search]\nDo the search.",
                Skill.renderBody(skill("doc.search", "Finds docs.", "Do the search.")));
    }

    @Test
    void renderForSystemPromptIncludesDescriptionWhenPresent() {
        assertEquals("[Skill: doc.search]\nFinds docs.\n\nDo the search.",
                Skill.renderForSystemPrompt(skill("doc.search", "Finds docs.", "Do the search.")));
    }

    @Test
    void aMissingBodyRendersAsTheBareHeader() {
        assertEquals("[Skill: doc.search]\n", Skill.renderBody(skill("doc.search", null, null)),
                "a body-less skill renders its header and nothing invented");
    }

    @Test
    void renderForSystemPromptOmitsBlankOrMissingDescription() {
        assertEquals("[Skill: doc.search]\nDo the search.",
                Skill.renderForSystemPrompt(skill("doc.search", "", "Do the search.")));
        assertEquals("[Skill: doc.search]\nDo the search.",
                Skill.renderForSystemPrompt(skill("doc.search", null, "Do the search.")));
    }
}
