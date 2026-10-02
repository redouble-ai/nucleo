/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import java.lang.annotation.*;

/**
 * Annotation to specify the name of a tool for LLM invocation.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-19)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolName {
    String value();
}