/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt.skill;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the SKILL.md parsing refusals and fallbacks of {@link SkillJarsLoader}, driven on
 * fixture files that live OUTSIDE {@code META-INF/skills/} so the classpath auto-scan
 * never trips over the deliberately broken ones: a bundle without a description - which
 * includes a SKILL.md with no frontmatter block at all - is refused; a description past
 * the Agent Skills specification's 1024-character ceiling is refused; {@code allowed-tools}
 * must be a list and {@code metadata} a map; an undeclared name defaults to the leaf
 * directory; and a nested {@code metadata.license} overrides the top-level field.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class SkillMdParsingTest {

    @BeforeEach
    void reset() {
        SkillRegistry.resetAll();
    }

    private static Skill load(String fixture) {
        return SkillJarsLoader.loadSkill(Thread.currentThread().getContextClassLoader(),
                fixture, "skilljar-fixtures/" + fixture + "/SKILL.md", Set.of());
    }

    @Test
    void aBundleWithoutADescriptionIsRefused() {
        SkillLoadException refusal = assertThrows(SkillLoadException.class, () -> load("missing-description"),
                "description is required by the Agent Skills specification");
        assertTrue(refusal.getMessage().contains("description"), refusal.getMessage());
    }

    @Test
    void aFrontmatterLessFileIsRefusedForWantOfADescription() {
        assertThrows(SkillLoadException.class, () -> load("no-frontmatter"),
                "no frontmatter means no description, and description is required");
    }

    @Test
    void aDescriptionPastTheSpecificationCeilingIsRefused() {
        SkillLoadException refusal = assertThrows(SkillLoadException.class, () -> load("long-description"),
                "the specification caps a description at 1024 characters");
        assertTrue(refusal.getMessage().contains("1024"), refusal.getMessage());
    }

    @Test
    void allowedToolsMustBeAList() {
        SkillLoadException refusal = assertThrows(SkillLoadException.class, () -> load("bad-tools"));
        assertTrue(refusal.getMessage().contains("allowed-tools"), refusal.getMessage());
    }

    @Test
    void metadataMustBeAMap() {
        SkillLoadException refusal = assertThrows(SkillLoadException.class, () -> load("bad-metadata"));
        assertTrue(refusal.getMessage().contains("metadata"), refusal.getMessage());
    }

    @Test
    void unknownFieldsAndCompatibilityAreIgnored() {
        Skill skill = load("extra-fields");
        assertEquals("extra-fields", skill.name(),
                "a compatibility constraint and made-up fields never fail a bundle - they are ignored");
        assertEquals("Fixture Author", skill.metadata().getAuthor(),
                "the known nested fields still land while the unknown ones are skipped");
    }

    @Test
    void anUndeclaredNameDefaultsToTheLeafDirectory() {
        Skill skill = load("leaf-name-fallback");
        assertEquals("leaf-name-fallback", skill.name(),
                "the bundle directory names the skill when the frontmatter does not");
        assertTrue(skill.body().content().asText().startsWith("The body of the unnamed skill."));
    }

    @Test
    void nestedMetadataLicenseOverridesTheTopLevelField() {
        Skill skill = load("license-override");
        assertEquals("Apache-2.0", skill.metadata().getLicense(),
                "the nested block wins so authors can keep everything in one place");
        assertEquals("builtin", skill.metadata().getOrigin(),
                "a bundle-declared origin replaces the loader's skillsjars default");
    }
}
