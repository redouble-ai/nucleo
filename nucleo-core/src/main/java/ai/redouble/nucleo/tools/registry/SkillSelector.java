/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.prompt.skill.*;
import org.slf4j.*;
import java.util.*;

/**
 * Names the skills a thinker may pull into its conversation at run time. The counterpart of
 * {@link ToolSelector} for the skill catalog: a thinker returns one from
 * {@code declareCompatibleSkills()}, and {@code ToolHub} resolves it once per thinker class
 * into the set that {@code request_skill} publishes.
 *
 * <p>Three ways to name skills, combinable: by exact name, by bundle id prefix (every skill
 * whose {@code metadata.bundle_id} starts with it, so one line admits a whole skilljar), or
 * everything the registry holds. Resolution happens at {@link #getSkills()}, against the
 * registry as it stands then, so a skilljar that arrived on the classpath is seen without the
 * declaration naming it.
 *
 * <p>A declared name that the registry does not hold is logged and skipped, the same way a
 * thinker's default skills behave: the palette is built every turn, and a missing bundle must
 * not take the thinker down with it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public class SkillSelector {
    private static final Logger log = LoggerFactory.getLogger(SkillSelector.class);
    private final Set<String> names = new LinkedHashSet<>();
    private final Set<String> bundlePrefixes = new LinkedHashSet<>();
    private boolean everything;

    public SkillSelector() {
    }

    public SkillSelector(String... skillNames) {
        addSkill(skillNames);
    }

    /** Skills by exact registry name. */
    public void addSkill(String... skillNames) {
        Collections.addAll(names, skillNames);
    }

    /** Every skill whose bundle id starts with the prefix, e.g. {@code ai.redouble.skills.}. */
    public void addBundle(String bundleIdPrefix) {
        bundlePrefixes.add(bundleIdPrefix);
    }

    /** Every skill the registry holds when the selector is resolved. */
    public void addAll() {
        everything = true;
    }

    /** The resolved set, in declaration order; the registry is consulted now, not at declaration. */
    public Set<Skill> getSkills() {
        Set<Skill> resolved = new LinkedHashSet<>();
        for (String name : names) {
            Skill skill = SkillRegistry.lookup(name);
            if (skill == null) {
                log.warn("SkillSelector: declared skill '{}' is not in SkillRegistry; skipping", name);
                continue;
            }
            resolved.add(skill);
        }
        if (everything || !bundlePrefixes.isEmpty()) {
            for (Skill skill : SkillRegistry.all()) {
                if (everything || inADeclaredBundle(skill)) {
                    resolved.add(skill);
                }
            }
        }
        return resolved;
    }

    private boolean inADeclaredBundle(Skill skill) {
        String bundleId = skill.metadata() == null ? null : skill.metadata().getBundleId();
        if (bundleId == null) {
            return false;
        }
        for (String prefix : bundlePrefixes) {
            if (bundleId.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
