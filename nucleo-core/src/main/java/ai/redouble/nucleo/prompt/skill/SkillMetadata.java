/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt.skill;

import ai.redouble.nucleo.prompt.*;

/**
 * Provenance and admission metadata for a {@link Skill}. Extends {@link PromptContext}
 * because a Skill has the same lineage shape (content hash over the full bundle,
 * production time, optional version and variant) plus skill-specific fields.
 *
 * <p>Origin values: {@code "builtin"} for framework-shipped skills, {@code "skillsjars"}
 * for bundles other artifacts carry, {@code "user-bundle"} for content an app loads at
 * runtime. A bundle writes its own frontmatter, so origin is a label rather than a
 * finding, and nothing in the framework reads it; {@link SkillJarsLoader} logs each
 * registered bundle with its declared origin as an inventory line (see the package doc's
 * Origin section for why build-time content carries the trust of a source literal).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
public class SkillMetadata extends PromptContext {
    private String author;
    private String license;
    private String origin;
    private String bundleId;
    private String triggerKeyword;

    public SkillMetadata() {
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getLicense() {
        return license;
    }

    public void setLicense(String license) {
        this.license = license;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getBundleId() {
        return bundleId;
    }

    public void setBundleId(String bundleId) {
        this.bundleId = bundleId;
    }

    public String getTriggerKeyword() {
        return triggerKeyword;
    }

    public void setTriggerKeyword(String triggerKeyword) {
        this.triggerKeyword = triggerKeyword;
    }
}
