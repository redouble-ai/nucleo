/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import java.lang.annotation.*;

/**
 * Provides a description of what a field or class should contain.
 * This description is used when generating LLM prompts to guide the model
 * on what content to generate for this field or overall response.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-16)
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LLMDescription {
    /**
     * Description of the field's expected content, including any constraints.
     * Example: "A confidence score from 1-10 where 10 is highest confidence"
     */
    String value();
}