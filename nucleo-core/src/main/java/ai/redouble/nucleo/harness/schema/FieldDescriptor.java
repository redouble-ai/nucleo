/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/**
 * Describes a field within a POJO definition for LLM schema generation.
 * Supports nested object definitions and array types.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-14)
 */
public class FieldDescriptor {
    /** JSON Schema's reference keyword, and the path its targets live under on the root. */
    static final String REF = "$ref";
    static final String DEFS_PREFIX = "#/$defs/";
    private String name;
    private String description;
    private String type;
    private PojoDefinition definition;
    private List<String> enumValues;
    private List<String> examples;
    private boolean required;
    private ObjectNode extensions;
    private String reference;

    public FieldDescriptor() {
    }

    public FieldDescriptor(String name, String description, String type) {
        this.name = name;
        this.description = description;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public PojoDefinition getDefinition() {
        return definition;
    }

    public void setDefinition(PojoDefinition definition) {
        this.definition = definition;
    }

    /**
     * The accepted wire values of an enum-typed field, or of the elements of an
     * enum-typed collection, in the form the serializer reads them back.
     */
    public List<String> getEnumValues() {
        return enumValues;
    }

    public void setEnumValues(List<String> enumValues) {
        this.enumValues = enumValues;
    }

    /**
     * The field's declared examples, as authored.
     */
    public List<String> getExamples() {
        return examples;
    }

    public void setExamples(List<String> examples) {
        this.examples = examples;
    }

    /**
     * Whether the field is declared {@code @LLMRequired}. This is the authority for
     * required-ness everywhere: the description also carries a "(REQUIRED)" token for the
     * model to read, but that token is prose and is never parsed back into structure.
     */
    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    /**
     * The {@code x-nucleo-*} JSON Schema keywords this field carries: facts about the data
     * that a consuming harness needs and cannot derive, such as whether a field can be
     * summarized or must stay out of a model's context. JSON Schema ignores unknown
     * keywords, so they are inert for any consumer that does not know them.
     *
     * <p>Emitted by {@link #toJsonSchemaNode} only. The {@code @}-notation form is what a
     * model reads, and none of this is addressed to the model.
     */
    public ObjectNode getExtensions() {
        return extensions;
    }

    /**
     * Adds one {@code x-nucleo-*} keyword. The caller supplies the full keyword name.
     */
    public void putExtension(String keyword, JsonNode value) {
        if (extensions == null) {
            extensions = NucleoJsonSerializer.createObjectNode();
        }
        extensions.set(keyword, value);
    }

    /**
     * The name of a type this field refers back to rather than describing again, set when the
     * walker met a cycle. A schema for a self-referential shape is finite only because the
     * second occurrence is a reference: JSON Schema renders it as a {@code $ref} into the
     * root's {@code $defs} (an array of the type as an array whose items are that reference),
     * the {@code @}-notation as the type's bare name, or {@code array of <name>} for a
     * collection of it.
     */
    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String toLLMSchema() {
        return toLLMSchemaNode().toPrettyString();
    }

    /**
     * Generates LLM-formatted schema ObjectNode for this field.
     */
    ObjectNode toLLMSchemaNode() {
        ObjectNode node = NucleoJsonSerializer.createObjectNode();
        String cleanDescription = description;
        String examples = null;
        if (description != null) {
            cleanDescription = description.replace("(REQUIRED)", "").trim();
            int exampleStart = cleanDescription.indexOf("[Examples:");
            if (exampleStart != -1) {
                int exampleEnd = cleanDescription.indexOf("]", exampleStart);
                if (exampleEnd != -1) {
                    examples = cleanDescription.substring(exampleStart + 10, exampleEnd).trim();
                    cleanDescription = cleanDescription.substring(0, exampleStart).trim();
                }
            }
        }
        if (reference != null) {
            // Named, not described again: the model has already read this type in full at the
            // occurrence that contains this one.
            node.put("@type", type != null && (type.startsWith("List<") || type.startsWith("array of ") || "array".equals(type))
                    ? "array of " + reference
                    : reference);
        } else if (definition != null) {
            if (type != null && (type.startsWith("List<") || type.startsWith("array of "))) {
                node.put("@type", "array of " + definition.getClassName());
            } else {
                node.put("@type", definition.getClassName());
            }
        } else {
            node.put("@type", type);
        }
        if (enumValues != null) {
            ArrayNode values = NucleoJsonSerializer.createArrayNode();
            for (String value : enumValues) {
                values.add(value);
            }
            node.set("@values", values);
        }
        if (required) {
            node.put("@required", true);
        }
        if (cleanDescription != null && !cleanDescription.isEmpty()) {
            node.put("@description", cleanDescription);
        }
        if (examples != null) {
            ArrayNode examplesNode = NucleoJsonSerializer.createArrayNode();
            String[] exampleArray = examples.split(",");
            for (String ex : exampleArray) {
                String trimmed = ex.trim();
                try {
                    examplesNode.add(Integer.parseInt(trimmed));
                }
                catch (NumberFormatException e) {
                    examplesNode.add(trimmed);
                }
            }
            node.set("@examples", examplesNode);
        }
        if (definition != null && !definition.getFields().isEmpty()) {
            ObjectNode fieldsNode = NucleoJsonSerializer.createObjectNode();
            for (Map.Entry<String, FieldDescriptor> entry : definition.modelWrittenFields().entrySet()) {
                fieldsNode.set(entry.getKey(), entry.getValue().toLLMSchemaNode());
            }
            node.set("@fields", fieldsNode);
        }
        return node;
    }

    /**
     * Generates a standard JSON Schema representation of this field.
     *
     * @return JSON Schema string
     */
    public String toJsonSchema() {
        return toJsonSchemaNode(null).toPrettyString();
    }

    /**
     * Generates a standard JSON Schema ObjectNode for this field.
     *
     * @param descriptionOverride if non-null, replaces the real description
     * @return JSON Schema ObjectNode
     */
    ObjectNode toJsonSchemaNode(String descriptionOverride) {
        ObjectNode node = NucleoJsonSerializer.createObjectNode();
        if (isMap()) {
            // A map is an object with no declared properties and a value shape for every
            // key, which is what additionalProperties states. A boolean true says the value
            // may be anything, for a map to Object.
            node.put("type", "object");
            node.set("additionalProperties", mapValueSchema());
        }
        else if (reference != null) {
            // The cycle stops here. An array of the referring type keeps its array wrapper so
            // the shape stays honest; a plain field is the reference itself. The description,
            // the examples and the x-nucleo keywords are the field's own and follow below,
            // exactly as they do for a field that is described in full.
            ObjectNode target = NucleoJsonSerializer.createObjectNode();
            target.put(REF, DEFS_PREFIX + reference);
            if (type != null && (type.startsWith("List<") || type.startsWith("array of ") || "array".equals(type))) {
                node.put("type", "array");
                node.set("items", target);
            }
            else {
                node.put(REF, DEFS_PREFIX + reference);
            }
        }
        else if (definition != null) {
            if (type != null && (type.startsWith("List<") || type.startsWith("array of "))) {
                node.put("type", "array");
                node.set("items", definition.toJsonSchemaNode(Map.of()));
            } else {
                node.put("type", "object");
                node.put("title", definition.getClassName());
                if (!definition.getFields().isEmpty()) {
                    List<String> requiredFields = new ArrayList<>();
                    ObjectNode properties = NucleoJsonSerializer.createObjectNode();
                    for (Map.Entry<String, FieldDescriptor> entry : definition.getFields().entrySet()) {
                        properties.set(entry.getKey(), entry.getValue().toJsonSchemaNode(null));
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
            }
        } else if (enumValues != null) {
            // An enum is a string constrained to its constants; a collection of enums is an
            // array whose items are. Standard JSON Schema, so any consumer learns the choices.
            ObjectNode constrained = type != null && type.startsWith("array of ") ? NucleoJsonSerializer.createObjectNode() : node;
            constrained.put("type", "string");
            ArrayNode allowed = NucleoJsonSerializer.createArrayNode();
            for (String value : enumValues) {
                allowed.add(value);
            }
            constrained.set("enum", allowed);
            if (constrained != node) {
                node.put("type", "array");
                node.set("items", constrained);
            }
        } else {
            scalarSchema(node, type);
        }
        if (examples != null) {
            ArrayNode examplesNode = NucleoJsonSerializer.createArrayNode();
            for (String example : examples) {
                try {
                    examplesNode.add(Integer.parseInt(example));
                }
                catch (NumberFormatException e) {
                    examplesNode.add(example);
                }
            }
            node.set("examples", examplesNode);
        }
        if (descriptionOverride != null) {
            node.put("description", descriptionOverride);
        } else {
            String cleanDescription = description;
            if (cleanDescription != null) {
                cleanDescription = cleanDescription.replace("(REQUIRED)", "").trim();
                int exampleStart = cleanDescription.indexOf("[Examples:");
                if (exampleStart != -1) {
                    int exampleEnd = cleanDescription.indexOf("]", exampleStart);
                    if (exampleEnd != -1) {
                        cleanDescription = cleanDescription.substring(0, exampleStart).trim();
                    }
                }
                if (!cleanDescription.isEmpty()) {
                    node.put("description", cleanDescription);
                }
            }
        }
        if (extensions != null) {
            node.setAll(extensions);
        }
        return node;
    }

    /**
     * The schema of a value that is neither a nested definition nor an enum, written onto the
     * node. A field typed {@code Object} publishes no type at all: a schema that says nothing
     * accepts any JSON, which is the truth about such a field, where naming a type would be a
     * claim the value will not keep.
     */
    private static void scalarSchema(ObjectNode node, String type) {
        if ("any".equals(type)) {
            return;
        }
        String jsonSchemaType = mapToJsonSchemaType(type);
        node.put("type", jsonSchemaType);
        // A temporal field is a string of a particular shape. Saying only "string" leaves
        // a consumer to guess and leaves a boundary with nothing to check, so the shape
        // is published as the standard `format` and enforced from it.
        if ("date".equals(type) || "date-time".equals(type)) {
            node.put("format", type);
        }
        // "integer" says nothing about width, so a consumer is entitled to send 2^64 and
        // a boundary has nothing to refuse it with - it would be accepted and then
        // overflow or throw one layer down. The field's own width is known here, so it is
        // published as the standard bounds and enforced from them.
        if ("integer".equals(type)) {
            node.put("minimum", Integer.MIN_VALUE);
            node.put("maximum", Integer.MAX_VALUE);
        }
        else if ("long".equals(type)) {
            node.put("minimum", Long.MIN_VALUE);
            node.put("maximum", Long.MAX_VALUE);
        }
        // A collection of scalars still has an element type, and a schema that omits it
        // says an array of anything - which is what a consumer would then be entitled to
        // send. Composite elements come through the branch above as `items`; this is the
        // same statement for the scalar case.
        if ("array".equals(jsonSchemaType) && type.startsWith("array of ")) {
            String element = mapToJsonSchemaType(type.substring("array of ".length()));
            ObjectNode items = NucleoJsonSerializer.createObjectNode();
            items.put("type", element);
            node.set("items", items);
        }
    }

    private boolean isMap() {
        return type != null && (type.startsWith("map from ") || "map".equals(type));
    }

    /**
     * What every value of a map must be: the nested definition or its reference where the
     * walker found a composite, the constants where it found an enum, the scalar shape where
     * the prose names one, and {@code true} - any JSON - where it names Object or nothing.
     */
    private JsonNode mapValueSchema() {
        if (reference != null) {
            ObjectNode target = NucleoJsonSerializer.createObjectNode();
            target.put(REF, DEFS_PREFIX + reference);
            return target;
        }
        if (definition != null) {
            return definition.toJsonSchemaNode(Map.of());
        }
        ObjectNode value = NucleoJsonSerializer.createObjectNode();
        if (enumValues != null) {
            value.put("type", "string");
            ArrayNode allowed = NucleoJsonSerializer.createArrayNode();
            for (String constant : enumValues) {
                allowed.add(constant);
            }
            value.set("enum", allowed);
            return value;
        }
        int to = type.indexOf(" to ");
        String named = to < 0 ? "Object" : type.substring(to + " to ".length());
        if (!KNOWN_SCALARS.contains(named.toLowerCase()) && !named.startsWith("array of ")) {
            return BooleanNode.TRUE;
        }
        scalarSchema(value, named);
        return value;
    }

    /** The prose type names {@link #scalarSchema} can turn into a JSON Schema type. */
    private static final Set<String> KNOWN_SCALARS = Set.of("string", "int", "integer", "long", "double", "float", "number",
            "boolean", "bool", "date", "date-time");

    /**
     * Maps our internal type names to JSON Schema types.
     */
    private static String mapToJsonSchemaType(String ourType) {
        if (ourType == null) return "string";
        return switch (ourType.toLowerCase()) {
            case "string" -> "string";
            case "int", "integer", "long" -> "integer";
            case "double", "float", "number" -> "number";
            case "boolean", "bool" -> "boolean";
            default -> {
                if (ourType.startsWith("List<") || ourType.startsWith("array of ")) {
                    yield "array";
                }
                yield "string";
            }
        };
    }
}
