/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt.skill;

import ai.redouble.nucleo.prompt.*;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.read.*;
import org.junit.jupiter.api.*;
import org.slf4j.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link SkillJarsLoader} discovers {@code META-INF/skills/**\/SKILL.md} bundles
 * on the classpath, parses the SKILL.md frontmatter + body per the Agent Skills
 * specification, and registers the result in {@link SkillRegistry}. Fixtures live under
 * {@code src/test/resources/META-INF/skills/}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-22)
 */
public class SkillJarsLoaderTest {
    @BeforeEach
    void reset() {
        SkillRegistry.resetAll();
    }

    /** Captures what the loader logged during one scan, root-attached so any logger name is seen. */
    private static List<String> inventoryFromAScan() {
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        captured.setContext(root.getLoggerContext());
        captured.start();
        root.addAppender(captured);
        try {
            SkillJarsLoader.scan();
        }
        finally {
            root.detachAppender(captured);
            captured.stop();
        }
        return captured.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("SkillJarsLoader: skill '"))
                .toList();
    }

    /**
     * The registered set is an inventory an operator can read: which skill is live and which
     * classpath resource carried it. The resource is the useful half - a skill name says nothing
     * about which jar to go and look in when the prompt in production is not the expected one.
     */
    @Test
    void everyRegisteredSkillIsListedWithTheResourceThatCarriedIt() {
        List<String> listed = inventoryFromAScan();
        assertTrue(listed.stream().anyMatch(line -> line.contains("'test-skill'")
                        && line.contains("META-INF/skills/test-skill/SKILL.md")
                        && line.contains("declared origin: skillsjars")),
                "the flat bundle is named together with the resource that carried it and the origin"
                        + " the bundle declared: " + listed);
        assertTrue(listed.stream().anyMatch(line -> line.contains("'nested-skill'")
                        && line.contains("org-example/repo-example/nested-skill/SKILL.md")),
                "a bundle nested three levels deep is listed the same way: " + listed);
        assertEquals(listed.size(), listed.stream().distinct().count(),
                "one line per bundle rather than a bundle listed twice: " + listed);
    }

    /**
     * The skills this project ships are not a separate category. They live in the
     * {@code nucleo-skills} artifact and reach the registry through this same loader,
     * which is why {@code builtin} and {@code skillsjars} describe which jar carried a file and
     * not how far it is trusted: both were fixed when the artifact set was assembled.
     */
    @Test
    void theProjectsOwnShippedSkillsArriveThroughThisLoaderToo() {
        List<String> listed = inventoryFromAScan();
        assertNotNull(SkillRegistry.lookup("delegation"),
                "the shipped skills do arrive through this loader rather than being registered directly");
        assertTrue(listed.stream().anyMatch(line -> line.contains("'delegation'")),
                "and they are listed like any other bundle, because at load time they are another "
                        + "jar on the classpath: " + listed);
    }


    @Test
    void loadsFlatBundle() {
        SkillJarsLoader.scan();
        Skill skill = SkillRegistry.lookup("test-skill");
        assertNotNull(skill, "test-skill should be discovered from META-INF/skills/");
        assertEquals("test-skill", skill.name());
    }

    @Test
    void loadsNestedBundle() {
        SkillJarsLoader.scan();
        Skill skill = SkillRegistry.lookup("nested-skill");
        assertNotNull(skill, "SkillsJars-style nested path (org/repo/skill) must be discovered");
        assertEquals("nested-skill", skill.name());
    }

    @Test
    void parsesDescriptionAndBody() {
        SkillJarsLoader.scan();
        Skill skill = SkillRegistry.lookup("test-skill");
        assertTrue(skill.description().content().asText()
            .startsWith("A fixture skill used by SkillJarsLoaderTest"));
        assertTrue(skill.body().content().asText()
            .startsWith("You are a test skill body."));
    }

    @Test
    void parsesAllowedTools() {
        SkillJarsLoader.scan();
        Skill skill = SkillRegistry.lookup("test-skill");
        assertEquals(2, skill.suggestedTools().size());
        assertTrue(skill.suggestedTools().contains("get_artifact_field"));
        assertTrue(skill.suggestedTools().contains("search_supporting_documents"));
    }

    @Test
    void parsesTopLevelLicenseAndNestedMetadata() {
        SkillJarsLoader.scan();
        Skill skill = SkillRegistry.lookup("test-skill");
        SkillMetadata md = skill.metadata();
        assertEquals("Apache-2.0", md.getLicense(), "top-level license");
        assertEquals("Redouble AI Tests", md.getAuthor(), "nested metadata.author");
        assertEquals("ai.redouble.test.test-skill", md.getBundleId(), "nested metadata.bundle_id");
        assertEquals("test", md.getTriggerKeyword(), "nested metadata.trigger_keyword");
        assertEquals("skillsjars", md.getOrigin(), "default origin");
    }

    @Test
    void loadsSiblingResources() {
        SkillJarsLoader.scan();
        Skill skill = SkillRegistry.lookup("test-skill");
        Prompt templatePrompt = skill.resources().get("templates/example-template.md");
        assertNotNull(templatePrompt, "templates/example-template.md should be registered as a resource");
        assertTrue(templatePrompt.content().asText().startsWith("# Example Template"));
    }

    @Test
    void skillPromptsWearTheSkillsjarsNamespace() {
        SkillJarsLoader.scan();
        Skill skill = SkillRegistry.lookup("test-skill");
        assertEquals("skillsjars:test-skill:body", skill.body().key(),
                "skill prompts are registered prompt keys in the reserved colon namespace");
        assertEquals("skillsjars:test-skill:description", skill.description().key());
    }

    @Test
    void aNestedBundleInsideAnotherSkillIsItsOwnSkill_neverTheOuterOnesResource() {
        SkillJarsLoader.scan();
        assertNotNull(SkillRegistry.lookup("inner-skill"),
                "a SKILL.md nested inside another skill's directory registers as its own bundle");
        Skill outer = SkillRegistry.lookup("test-skill");
        assertFalse(outer.resources().containsKey("inner-skill/SKILL.md"),
                "and is skipped from the outer skill's resources");
    }

    @Test
    void registerReplacesAPriorNameWithAWarning() {
        Skill first = new TextSkill("dup-skill", null, null, null, null, new SkillMetadata());
        Skill second = new TextSkill("dup-skill", null, null, null, null, new SkillMetadata());
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        captured.setContext(root.getLoggerContext());
        captured.start();
        root.addAppender(captured);
        try {
            SkillRegistry.register(first);
            SkillRegistry.register(second);
        }
        finally {
            root.detachAppender(captured);
            captured.stop();
        }
        assertSame(second, SkillRegistry.lookup("dup-skill"), "last write wins");
        assertTrue(captured.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("replacing existing skill 'dup-skill'")),
                "the replacement leaves an audit trail at WARN");
    }

    @Test
    void allAndContainsAutoTriggerTheScanToo() {
        assertTrue(SkillRegistry.contains("test-skill"), "contains() scans on first access");
        SkillRegistry.resetAll();
        assertFalse(SkillRegistry.all().isEmpty(), "all() scans on first access");
    }

    @Test
    void aLookupRacingTheFirstScanSeesAPopulatedRegistry() throws Exception {
        int racers = 16;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Skill>> results = new ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < racers; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return SkillRegistry.lookup("test-skill");
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<Skill> result : results) {
                assertNotNull(result.get(10, java.util.concurrent.TimeUnit.SECONDS),
                        "the scan is blocking-idempotent: a lookup racing the very first scan"
                                + " waits for it rather than reading an empty registry");
            }
        }
    }

    @Test
    void scanIsIdempotent() {
        SkillJarsLoader.scan();
        Skill first = SkillRegistry.lookup("test-skill");
        SkillJarsLoader.scan();
        Skill second = SkillRegistry.lookup("test-skill");
        assertSame(first, second, "idempotent scan should not re-register");
    }

    @Test
    void lookupAutoTriggersScan() {
        Skill skill = SkillRegistry.lookup("test-skill");
        assertNotNull(skill, "SkillRegistry.lookup should auto-trigger SkillJarsLoader.scan");
    }
}
