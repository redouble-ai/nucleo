/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import com.fasterxml.jackson.databind.node.*;

/**
 * Narrows a tool's generated input schema with what is known only at runtime.
 *
 * <p>The schema of a tool is generated from its input class, which states each parameter's
 * type and meaning but cannot state a value set that exists only once the application has
 * resolved - the columns of a table, the names of a registry. A model told "a column name" and
 * left to guess it is refused and re-called; a model told the names never guesses. A refiner
 * writes those names into the schema as {@code enum}, and the same schema then reaches every
 * reader: the model's tool definition, the MCP publication, and the gate that judges a call.
 *
 * <p>Declared on the tool class with {@link SchemaRefinedBy}; constructed by the provider
 * through a public no-argument constructor, once per provider.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-09)
 */
public interface SchemaRefiner {
    /**
     * @param inputType   the tool's input class the schema was generated from, for a refiner
     *                    shared by tools with different inputs
     * @param inputSchema the generated JSON Schema, refined in place
     */
    void refine(Class<?> inputType, ObjectNode inputSchema);
}
