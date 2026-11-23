/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import java.lang.annotation.*;

/**
 * What the model is told about a thing it works with: on a tool class, what the tool does and
 * when to use it; on an input, output or artifact class, what the type is; on a field, what
 * the field holds, including any constraints. It is the {@code description} of the tool's
 * definition and of the JSON Schema at every level. The name people see is a separate text,
 * {@link ai.redouble.nucleo.harness.DisplayName}.
 *
 * <p>Nucleo reads it on classes and fields. It may also stand on a method, for a host whose
 * properties are getters and which reads the annotation there itself.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-16)
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LLMDescription {
    /**
     * The description. For a field: its expected content, including any constraints, for
     * example "A confidence score from 1-10 where 10 is highest confidence".
     */
    String value();
}