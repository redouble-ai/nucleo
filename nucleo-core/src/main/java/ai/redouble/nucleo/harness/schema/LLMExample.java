/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import java.lang.annotation.*;

/**
 * Provides example values for a field to help guide LLM generation.
 * Examples are included in the generated schema to show the model
 * what kind of content is expected.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-16)
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LLMExample {
    /**
     * One or more example values for this field.
     */
    String[] value();
}