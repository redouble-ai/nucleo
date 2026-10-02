/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.prompt.skill.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SkillSelector} names a thinker's admissible skills three ways and resolves them
 * against the registry at resolution time, not at declaration.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public class SkillSelectorTest {
    private static final Skill CITE = SkillFixtures.skill("sel.cite", "Cite.", "ai.example.writing.cite");
    private static final Skill BRIEF = SkillFixtures.skill("sel.brief", "Brief.", "ai.example.writing.brief");
    private static final Skill AUDIT = SkillFixtures.skill("sel.audit", "Audit.", "ai.example.compliance.audit");

    @BeforeEach
    void register() {
        SkillRegistry.register(CITE);
        SkillRegistry.register(BRIEF);
        SkillRegistry.register(AUDIT);
    }

    private static Set<String> names(Set<Skill> skills) {
        Set<String> names = new LinkedHashSet<>();
        for (Skill skill : skills) {
            names.add(skill.name());
        }
        return names;
    }

    @Test
    void byName_inDeclarationOrder() {
        SkillSelector selector = new SkillSelector("sel.brief", "sel.cite");
        assertEquals(List.of("sel.brief", "sel.cite"), new ArrayList<>(names(selector.getSkills())));
    }

    @Test
    void anUnregisteredNameIsSkipped_theRestResolve() {
        SkillSelector selector = new SkillSelector("sel.cite", "sel.no-such-skill");
        assertEquals(Set.of("sel.cite"), names(selector.getSkills()),
                "a declared name the registry does not hold must not take the palette down with it");
    }

    @Test
    void byBundlePrefix_admitsAWholeBundleAndNothingBeside() {
        SkillSelector selector = new SkillSelector();
        selector.addBundle("ai.example.writing.");
        Set<String> resolved = names(selector.getSkills());
        assertTrue(resolved.containsAll(Set.of("sel.cite", "sel.brief")), resolved.toString());
        assertFalse(resolved.contains("sel.audit"), "a different bundle is not admitted by a prefix it does not share");
    }

    @Test
    void everything_seesWhatTheRegistryHoldsAtResolution() {
        SkillSelector selector = new SkillSelector();
        selector.addAll();
        Skill late = SkillFixtures.skill("sel.late", "Arrived after the declaration.", "ai.example.late");
        SkillRegistry.register(late);
        Set<String> resolved = names(selector.getSkills());
        assertTrue(resolved.containsAll(Set.of("sel.cite", "sel.brief", "sel.audit", "sel.late")),
                "resolution reads the registry as it stands, so a skilljar that arrived later is offered: " + resolved);
    }

    @Test
    void namesAndBundlesCombine_withoutDuplicates() {
        SkillSelector selector = new SkillSelector("sel.cite");
        selector.addBundle("ai.example.writing.");
        List<String> resolved = new ArrayList<>(names(selector.getSkills()));
        assertEquals(2, resolved.size(), "a skill named twice, by name and by bundle, is offered once: " + resolved);
        assertEquals("sel.cite", resolved.get(0), "explicit names come first");
    }
}
