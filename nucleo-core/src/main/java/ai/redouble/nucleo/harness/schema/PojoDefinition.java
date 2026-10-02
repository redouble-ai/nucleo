/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/**
 * Represents a hierarchical definition of a POJO structure.
 * Can describe any serializable POJO including nested objects and lists; a class with no
 * fields is a definition with an empty field map.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-12)
 */
public class PojoDefinition {
    private String className;
    private String description;
    private Map<String, FieldDescriptor> fields;
    private Map<String, PojoDefinition> referencedDefinitions;
    /** The wire name of an artifact's reference; the same constant the artifact registry keys on. */
    static final String REF_FIELD = ai.redouble.nucleo.harness.artifacts.ArtifactRegistry.REF_FIELD;
    private boolean artifact;

    public PojoDefinition() {
        this.fields = new LinkedHashMap<>();
    }

    public PojoDefinition(String className, String description) {
        this.className = className;
        this.description = description;
        this.fields = new LinkedHashMap<>();
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, FieldDescriptor> getFields() {
        return fields;
    }

    public void setFields(Map<String, FieldDescriptor> fields) {
        this.fields = fields;
    }

    public void addField(String name, FieldDescriptor field) {
        this.fields.put(name, field);
    }

    /** Whether the described class is an artifact. */
    public boolean isArtifact() {
        return artifact;
    }

    public void setArtifact(boolean artifact) {
        this.artifact = artifact;
    }

    /**
     * The fields a model is asked to write for this type. For an artifact that is its
     * reference alone: a model never authors an artifact, it refers to one the registry
     * lists, and the artifact's content is shown to it there and nowhere in a schema.
     */
    public Map<String, FieldDescriptor> modelWrittenFields() {
        if (!artifact) {
            return fields;
        }
        Map<String, FieldDescriptor> reference = new LinkedHashMap<>();
        reference.put(REF_FIELD, fields.get(REF_FIELD));
        return reference;
    }

    public String toLLMSchema() {
        return toLLMSchemaNode().toPrettyString();
    }

    ObjectNode toLLMSchemaNode() {
        ObjectNode node = NucleoJsonSerializer.createObjectNode();
        node.put("@type", className);
        if (description != null) {
            node.put("@description", description);
        }
        if (!fields.isEmpty()) {
            ObjectNode fieldsNode = NucleoJsonSerializer.createObjectNode();
            for (Map.Entry<String, FieldDescriptor> entry : modelWrittenFields().entrySet()) {
                fieldsNode.set(entry.getKey(), entry.getValue().toLLMSchemaNode());
            }
            node.set("@fields", fieldsNode);
        }
        return node;
    }

    /**
     * Generates a standard JSON Schema representation of this POJO definition.
     * Compatible with JSON Schema draft-07 and later.
     *
     * @return JSON Schema string
     */
    public String toJsonSchema() {
        return toJsonSchemaNode(Map.of()).toPrettyString();
    }

    /**
     * Generates JSON Schema with descriptions overridden for the specified fields.
     * Used for shared field deduplication: fields whose descriptions are defined
     * once in a preamble get $PARAM- reference tokens instead of their full descriptions.
     *
     * @param descriptionOverrides maps field name to description override (e.g. "query" to "$PARAM-QUERY")
     * @return JSON Schema string
     */
    public String toJsonSchema(Map<String, String> descriptionOverrides) {
        return toJsonSchemaNode(descriptionOverrides).toPrettyString();
    }

    /**
     * The definitions a {@code $ref} in this schema points at, keyed by type name. Populated
     * on the ROOT definition only, and only for a type the walker came back to; a schema
     * with no cycle in it carries no {@code $defs} section at all.
     */
    public void setReferencedDefinitions(Map<String, PojoDefinition> referencedDefinitions) {
        this.referencedDefinitions = referencedDefinitions;
    }

    ObjectNode toJsonSchemaNode(Map<String, String> descriptionOverrides) {
        return toJsonSchemaNode(descriptionOverrides, true);
    }

    /**
     * @param withDefinitions whether to emit the {@code $defs} section. True at the outermost
     *     render and false inside it: a self-referential type IS its own referenced
     *     definition, so a {@code $defs} entry that emitted its own {@code $defs} would
     *     descend forever - the cycle the walker stopped, reappearing in the renderer.
     */
    private ObjectNode toJsonSchemaNode(Map<String, String> descriptionOverrides, boolean withDefinitions) {
        ObjectNode node = NucleoJsonSerializer.createObjectNode();
        node.put("type", "object");
        // The type's name, in the keyword JSON Schema reserves for it. Without it a nested
        // type is an anonymous object to every reader; with it the schema names types the
        // way the @-notation's @type does, and the name survives any client that inlines.
        node.put("title", className);
        if (withDefinitions && referencedDefinitions != null && !referencedDefinitions.isEmpty()) {
            ObjectNode defs = NucleoJsonSerializer.createObjectNode();
            for (Map.Entry<String, PojoDefinition> entry : referencedDefinitions.entrySet()) {
                defs.set(entry.getKey(), entry.getValue().toJsonSchemaNode(Map.of(), false));
            }
            node.set("$defs", defs);
        }
        if (description != null && !description.isEmpty()) {
            node.put("description", description);
        }
        if (!fields.isEmpty()) {
            List<String> requiredFields = new ArrayList<>();
            ObjectNode properties = NucleoJsonSerializer.createObjectNode();
            for (Map.Entry<String, FieldDescriptor> entry : fields.entrySet()) {
                String override = descriptionOverrides.get(entry.getKey());
                properties.set(entry.getKey(), entry.getValue().toJsonSchemaNode(override));
                if (entry.getValue().isRequired()) {
                    requiredFields.add(entry.getKey());
                }
            }
            node.set("properties", properties);
            if (!requiredFields.isEmpty()) {
                ArrayNode required = NucleoJsonSerializer.createArrayNode();
                for (String f : requiredFields) {
                    required.add(f);
                }
                node.set("required", required);
            }
        }
        return node;
    }
}
