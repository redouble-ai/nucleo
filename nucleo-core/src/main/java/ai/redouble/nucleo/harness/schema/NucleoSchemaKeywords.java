/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.util.*;

/**
 * The {@code x-nucleo-*} JSON Schema vocabulary: keywords that carry a field's Nucleo
 * annotations to a consumer that cannot read the annotations themselves.
 *
 * <p>A schema has two audiences. The standard keywords - {@code type}, {@code description},
 * {@code required}, {@code enum}, {@code examples} - are what a model reads in a tool
 * definition. These keywords are what a consuming HARNESS reads: whether a field can be
 * summarized and how, whether it belongs in a model's context at all, what an
 * artifact-typed field points at. In process a harness reads the annotations off the class;
 * across an MCP boundary, where the concrete class may not resolve, the schema is the only
 * source. JSON Schema ignores unknown keywords, so they are inert for anyone else.
 *
 * <p>{@link #stripFrom} removes them again on the path to a model, which is why the prefix
 * has to be stable: it is what the strip keys on.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-04)
 */
public final class NucleoSchemaKeywords {
    /** The prefix every harness-facing keyword carries, and what {@link #stripFrom} keys on. */
    public static final String PREFIX = "x-nucleo-";
    /** {@code @LLMSummarizable}, as declared: hint, size, threshold, llmSafe, preSummarized, staticSummary. */
    public static final String SUMMARIZABLE = PREFIX + "summarizable";
    /** {@code @LLMContextIgnore}: present in the payload, kept out of a model's context. */
    public static final String CONTEXT_IGNORE = PREFIX + "context-ignore";
    /** The {@code @TypeAlias} of an artifact-typed field, which is also what its refs carry. */
    public static final String TYPE_ALIAS = PREFIX + "type-alias";

    private NucleoSchemaKeywords() {
    }

    /**
     * Returns the schema with every {@code x-nucleo-*} keyword removed, at every depth.
     *
     * @param schemaJson a JSON Schema document, or null or empty
     * @return the stripped schema; null for a null input and empty for an empty one
     * @throws IOException if the input is not JSON
     */
    public static String stripFrom(String schemaJson) throws IOException {
        if (schemaJson == null || schemaJson.isEmpty()) {
            return schemaJson;
        }
        JsonNode root = NucleoJsonSerializer.readTree(schemaJson);
        strip(root);
        return NucleoJsonSerializer.writeCompact(root);
    }

    /**
     * Returns the schema as a model is offered it: every artifact-typed field reduced to its
     * reference, then every {@code x-nucleo-*} keyword removed. A model never authors an
     * artifact, so it is never asked for one's content: where an input takes an artifact,
     * the model writes the reference of one the registry lists.
     *
     * @param schemaJson a JSON Schema document, or null or empty
     * @return the model-facing schema; null for a null input and empty for an empty one
     * @throws IOException if the input is not JSON
     */
    public static String forModel(String schemaJson) throws IOException {
        if (schemaJson == null || schemaJson.isEmpty()) {
            return schemaJson;
        }
        JsonNode root = NucleoJsonSerializer.readTree(schemaJson);
        referencesForArtifacts(root);
        strip(root);
        return NucleoJsonSerializer.writeCompact(root);
    }

    /**
     * Reduces every schema node that carries {@link #TYPE_ALIAS}, the mark of an
     * artifact-typed field, to an object whose one property is the artifact's reference.
     * An array of artifacts is reduced in its items.
     */
    private static void referencesForArtifacts(JsonNode node) {
        if (node instanceof ObjectNode object) {
            if (object.has(TYPE_ALIAS)) {
                JsonNode shape = "array".equals(object.path("type").asText()) ? object.path("items") : object;
                if (shape instanceof ObjectNode artifact && artifact.path("properties") instanceof ObjectNode properties
                        && properties.has(PojoDefinition.REF_FIELD)) {
                    properties.retain(PojoDefinition.REF_FIELD);
                    artifact.set("required", NucleoJsonSerializer.createArrayNode().add(PojoDefinition.REF_FIELD));
                    return;
                }
            }
            for (JsonNode child : object) {
                referencesForArtifacts(child);
            }
        }
        else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                referencesForArtifacts(child);
            }
        }
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> doomed = new ArrayList<>();
            Iterator<String> names = object.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (name.startsWith(PREFIX)) {
                    doomed.add(name);
                }
            }
            object.remove(doomed);
            for (JsonNode child : object) {
                strip(child);
            }
        }
        else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                strip(child);
            }
        }
    }
}
