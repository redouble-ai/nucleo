/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.sommelier;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.registry.*;
import com.fasterxml.jackson.databind.*;

import java.io.*;
import java.util.*;

/**
 * A tool as {@link ModelDrivenSommelier} shows it to a model: its name, its description, and the
 * words of its input and output definitions, one per line. The words are field names,
 * titles, descriptions and enumerated values; the structure of a JSON Schema ({@code type},
 * {@code required}, nesting) says nothing about what a tool is for and is left out.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
public final class ToolPage {

    /** The schema keywords whose values are words about the tool; every other keyword is structure. */
    private static final Set<String> WORD_KEYWORDS = Set.of("title", "description", "enum", "const", "examples");

    private ToolPage() {
    }

    public static String of(ToolProvider provider) throws IOException {
        return render(provider.name(), provider.description(), provider.schemaJson(), provider.outputSchemaJson());
    }

    /**
     * @param inputSchema the tool's input schema as JSON
     * @param outputSchema the tool's output schema as JSON, or null when the tool declares none
     */
    public static String render(String name, String description, String inputSchema, String outputSchema) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append(name).append('\n').append(description).append('\n');
        text.append("Input:\n");
        writeWords(NucleoJsonSerializer.readTree(inputSchema), text);
        if (outputSchema != null) {
            text.append("Output:\n");
            writeWords(NucleoJsonSerializer.readTree(outputSchema), text);
        }
        return text.toString();
    }

    private static void writeWords(JsonNode schema, StringBuilder text) {
        if (schema.isArray()) {
            for (JsonNode element : schema) {
                writeWords(element, text);
            }
            return;
        }
        if (!schema.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> field : schema.properties()) {
            String keyword = field.getKey();
            JsonNode value = field.getValue();
            if (WORD_KEYWORDS.contains(keyword)) {
                writeValue(value, text);
            }
            else if (keyword.equals("properties") || keyword.equals("$defs")) {
                // the keys here are field or type names, which are words; their values are schemas
                for (Map.Entry<String, JsonNode> named : value.properties()) {
                    text.append(named.getKey()).append('\n');
                    writeWords(named.getValue(), text);
                }
            }
            else {
                writeWords(value, text);
            }
        }
    }

    private static void writeValue(JsonNode value, StringBuilder text) {
        if (value.isArray()) {
            for (JsonNode element : value) {
                writeValue(element, text);
            }
        }
        else if (value.isValueNode()) {
            text.append(value.asText()).append('\n');
        }
    }
}
