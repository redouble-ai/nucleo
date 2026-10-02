/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.prompt.skill.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/**
 * Skills built in memory for the request_skill tests: a name, a description the model would
 * choose by, a body, and a bundle id so bundle selection has something to match.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
final class SkillFixtures {
    private SkillFixtures() {
    }

    static Prompt prompt(String key, String text) {
        return new Prompt() {
            public String key() {
                return key;
            }

            public JsonNode content() {
                return TextNode.valueOf(text);
            }

            public PromptContext context() {
                return null;
            }
        };
    }

    static Skill skill(String name, String description, String bundleId) {
        SkillMetadata metadata = new SkillMetadata();
        metadata.setBundleId(bundleId);
        metadata.setOrigin("test");
        return new TextSkill(name, prompt(name + ":description", description), prompt(name + ":body", "Body of " + name),
                Map.of(), List.of(), metadata);
    }
}
