/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import java.lang.annotation.*;

/**
 * Names the {@link SchemaRefiner} that narrows this tool's generated input schema with values
 * known only at runtime, so a caller is told every accepted name rather than left to guess one.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-09)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SchemaRefinedBy {
    Class<? extends SchemaRefiner> value();
}
