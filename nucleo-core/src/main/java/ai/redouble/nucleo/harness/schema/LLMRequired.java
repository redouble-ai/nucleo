/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import java.lang.annotation.*;

/**
 * Marks a field as required in LLM-generated responses: {@code @required} in the notation the
 * model reads, the {@code required} array of the JSON Schema, and what
 * {@code PojoResponseHandler.validateRequiredFields} checks on the instance. A required field
 * must be non-null, and a required String must not be blank; an empty collection is a valid
 * answer, meaning nothing was found.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-16)
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LLMRequired {
}