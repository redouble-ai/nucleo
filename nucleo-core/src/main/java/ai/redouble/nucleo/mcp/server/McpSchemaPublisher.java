/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.registry.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.modelcontextprotocol.json.*;
import io.modelcontextprotocol.spec.*;

import java.io.*;
import java.util.*;

/**
 * The outbound schema boundary: turns a {@link ToolProvider} into the {@link McpSchema.Tool}
 * an external client sees.
 * <p>
 * The generated schema ({@link ClassToolProvider#schemaJson()}) is already standard JSON
 * Schema with a proper {@code required} array. What this class adds is the boundary
 * rule: fields that carry references into this process's artifact registry do not
 * cross the process boundary, because a caller in another process has no registry to
 * resolve them against. Today that is one field, {@link ai.redouble.nucleo.tools.thinking.ThinkerInput}'s
 * artifact refs, published under {@link #ARTIFACT_REFS}; a caller that sends the key
 * anyway is sending an undeclared property and is refused by the gate like any other. A
 * tool whose input has no fields publishes the bare object schema the generator emits,
 * without a {@code properties} map.
 * <p>
 * The published input schema is closed at every object, root and nested, because the gate
 * refuses an undeclared property at every depth: the document a consumer reads and the
 * criterion its call is judged by are one statement. A dialect that cannot spell closure
 * (Nova at the root, Gemini anywhere) publishes an open description and is judged closed
 * regardless; its package documentation says so.
 * <p>
 * The output schema is published under a stricter contract than the input one, because the
 * SDK enforces it: declaring {@code outputSchema} obliges every non-error result to carry
 * {@code structuredContent} that validates against it. Two rules follow, both applied by
 * {@link #outputSchemaOf}. Only an object-shaped output is published at all - MCP structured
 * content is an object, so a tool answering a bare string or number publishes no output
 * schema and sends no structured content. And {@code required} is dropped: on an input it
 * means the caller must supply the field, while on an output {@code @LLMRequired} is a
 * directive to the model filling the POJO, never a guarantee to a consumer, so publishing it
 * would turn our own prompt guidance into a promise the validator holds us to.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-02)
 */
public final class McpSchemaPublisher {
    /**
     * The published name of {@code ThinkerInput.artifactRefs}. Pinned by test against the
     * generator's own key so a rename or a naming-strategy change fails the build.
     */
    public static final String ARTIFACT_REFS = "artifact_refs";
    /** `_meta` key carrying the tool's {@link ToolWeight} cost envelope. */
    public static final String WEIGHT_META = "ai.redouble/weight";
    private static final String PROPERTIES = "properties";
    private static final String REQUIRED = "required";

    private McpSchemaPublisher() {
    }

    /**
     * The tool as one dialect publishes it. The boundary rules apply to the canonical schema
     * first; the dialect then respells what is left. Whether structured content is sent is
     * decided on the canonical output schema and is the same on every dialect.
     */
    public static McpSchema.Tool publish(ToolProvider provider, SchemaDialect dialect) {
        JsonNode schema = canonicalInputSchema(provider);
        McpSchema.Tool.Builder builder = McpSchema.Tool.builder(provider.name(), McpJsonDefaults.getMapper(), NucleoJsonSerializer.writeCompact(dialect.inputSchema(schema)))
                .title(provider.displayName())
                .description(provider.description())
                .annotations(new McpSchema.ToolAnnotations(provider.displayName(), provider.readOnly(), null, null, null, null))
                .meta(weightMeta(provider.weight()));
        JsonNode outputSchema = outputSchemaOf(provider);
        if (outputSchema != null) {
            builder.outputSchema(McpJsonDefaults.getMapper(), NucleoJsonSerializer.writeCompact(dialect.outputSchema(outputSchema)));
        }
        return builder.build();
    }

    /**
     * The tool's canonical input schema: what every path publishes before its dialect
     * respells it, and the one document the gate judges by. The generator's schema is not
     * that document, because the boundary removes a field from it; a gate reading the
     * generator's tree would accept a property no path published. There is one acceptance
     * criterion, and this is it.
     */
    public static JsonNode canonicalInputSchema(ToolProvider provider) {
        JsonNode schema;
        try {
            schema = NucleoJsonSerializer.readTree(provider.schemaJson());
        }
        catch (IOException e) {
            throw new IllegalStateException("Generated input schema of tool " + provider.name() + " is not JSON", e);
        }
        stripBoundaryFields(schema);
        return schema;
    }

    /**
     * The tool's published output schema, or null when it has none to publish. The serving
     * layer asks the same question to decide whether to send structured content, so the
     * declaration and the response cannot disagree.
     *
     * @return the object-shaped output schema with {@code required} removed, or null
     */
    public static JsonNode outputSchemaOf(ToolProvider provider) {
        String generated = provider.outputSchemaJson();
        if (generated == null) {
            return null;
        }
        JsonNode schema;
        try {
            schema = NucleoJsonSerializer.readTree(generated);
        }
        catch (IOException e) {
            throw new IllegalStateException("Generated output schema of tool " + provider.name() + " is not JSON", e);
        }
        JsonNode type = schema.get("type");
        if (type == null || !"object".equals(type.asText())) {
            return null;
        }
        if (schema instanceof ObjectNode object) {
            object.remove(REQUIRED);
        }
        return schema;
    }

    /**
     * The tool's cost envelope, so a consumer can tell a seconds-long API wrapper from an
     * agent that will run for minutes. Nothing else in the MCP surface carries this: a
     * consumer cannot see {@code @ToolWeight}, and without it every tool looks alike.
     */
    private static Map<String, Object> weightMeta(ToolWeight weight) {
        return Map.of(WEIGHT_META, Map.of(
                "type", weight.type().name(),
                "level", weight.level(),
                "min", weight.min(),
                "max", weight.max()));
    }

    private static void stripBoundaryFields(JsonNode schema) {
        // The published contract says what we accept, and McpInputGate accepts exactly that,
        // at every depth. Declaring every object closed is what makes the two statements the
        // same statement.
        if (schema instanceof ObjectNode object) {
            SchemaRewrites.closeObjects(object);
        }
        JsonNode properties = schema.get(PROPERTIES);
        if (properties instanceof ObjectNode props) {
            props.remove(ARTIFACT_REFS);
        }
        JsonNode required = schema.get(REQUIRED);
        if (required instanceof ArrayNode names) {
            Iterator<JsonNode> it = names.elements();
            while (it.hasNext()) {
                if (ARTIFACT_REFS.equals(it.next().asText())) {
                    it.remove();
                }
            }
            if (names.isEmpty()) {
                ((ObjectNode)schema).remove(REQUIRED);
            }
        }
    }
}
