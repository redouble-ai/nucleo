/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt.skill;

/**
 * Raised by {@link SkillJarsLoader} when a classpath skill bundle fails to parse or register.
 * Unchecked because skill-bundle issues are authoring errors that should abort startup rather
 * than be caught at every call site.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-22)
 */
public class SkillLoadException extends RuntimeException {
    public SkillLoadException(String message) {
        super(message);
    }

    public SkillLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
