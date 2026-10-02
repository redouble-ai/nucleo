/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt.skill;


import org.slf4j.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Name-indexed lookup for {@link Skill} instances. Thin wrapper: holds registered skills
 * by name, iterates, returns null on unknown lookup.
 *
 * <p>Registration is idempotent per name: re-registering the same name replaces the prior
 * skill and emits a warning. This mirrors {@code Prompts.replace}: last-write-wins with
 * audit trail.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
public final class SkillRegistry {
    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);
    private static final Map<String, Skill> REGISTRY = new ConcurrentHashMap<>();

    private SkillRegistry() {
    }

    public static void register(Skill skill) {
        Skill prior = REGISTRY.put(skill.name(), skill);
        if (prior != null) {
            log.warn("SkillRegistry: replacing existing skill '{}'", skill.name());
        }
    }

    public static Skill lookup(String name) {
        triggerAutoScan();
        return REGISTRY.get(name);
    }

    public static Collection<Skill> all() {
        triggerAutoScan();
        return Collections.unmodifiableCollection(REGISTRY.values());
    }

    public static boolean contains(String name) {
        triggerAutoScan();
        return REGISTRY.containsKey(name);
    }

    /**
     * Fire {@link SkillJarsLoader#scan} on first access so classpath-resident skill bundles
     * land in the registry without explicit bootstrap. {@code scan()} is blocking-idempotent:
     * the first call scans, concurrent callers wait for it, later calls return immediately - so
     * a lookup that races the first scan still sees the populated registry. Calling it on every
     * lookup is intentional and cheap (a volatile read once scanned).
     */
    private static void triggerAutoScan() {
        SkillJarsLoader.scan();
    }

    /** Test-only. */
    public static void resetAll() {
        REGISTRY.clear();
        SkillJarsLoader.resetScanned();
    }
}
