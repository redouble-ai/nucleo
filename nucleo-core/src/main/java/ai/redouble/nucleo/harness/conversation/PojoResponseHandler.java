/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.node.*;

import org.slf4j.*;
import java.io.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;

/**
 * Generates LLM-friendly schema descriptions from POJOs annotated with
 * LLM and Jackson annotations. The generated schemas guide LLMs to produce
 * properly structured JSON responses.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-16)
 */
public class PojoResponseHandler<T > implements ResponseHandler<T> {
    private static final Logger log = LoggerFactory.getLogger(PojoResponseHandler.class);

    final Class<T> responseClass;

    public PojoResponseHandler(final Class<T> responseClass) {this.responseClass = responseClass;}

    @Override
    public T parse(final String rawContent) throws IOException {
        return NucleoJsonSerializer.parseLLMResponse(rawContent, this.getResponseClass());
    }

    @Override
    public String write(final T response) {
        return NucleoJsonSerializer.write(response);
    }

    @Override
    public List<String> getValidationErrors(T response) {
        return validationErrors(response);
    }

    @Override
    public PojoDefinition writeDefinition() {
        return buildPojoDefinition(this.responseClass);
    }

    @Override
    public boolean usesSchemaNotation() {
        return true;
    }

    @Override
    public String responseInstructions() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("\n\nRespond with valid JSON only, without markdown code blocks, explanations, or any other text.");
        prompt.append(" The response should be parseable JSON with no additional formatting or text.");
        prompt.append(" If the document content contains JSON examples or code snippets, analyze and summarize them - do not copy them verbatim into your response.");
        prompt.append(" Your response must contain ONLY the requested JSON object.\n");
        prompt.append(this.writeDefinition().toLLMSchema());
        return prompt.toString();
    }

    @Override
    public Class<T> getResponseClass() {
        return responseClass;
    }

    /**
     * Generates a PojoDefinition schema from a POJO class.
     * Reads both Jackson and LLM annotations to create comprehensive guidance.
     *
     * @param pojoClass the class to generate schema for
     * @return PojoDefinition representing the schema
     */
    public static PojoDefinition generateSchema(Class<?> pojoClass) {
        return buildPojoDefinition(pojoClass);
    }

    /**
     * The state of one walk: the types currently on the path, and the definitions of those
     * a cycle came back to.
     *
     * <p>A type graph may be cyclic - a crawled page whose subpages are a list of crawled
     * pages is the honest shape of the thing - and a walker that
     * inlines every composite it meets never terminates on one. Keyed on the PATH rather than
     * on everything seen, so two sibling fields of the same acyclic type are still described
     * in full; only a genuine cycle becomes a reference.
     */
    private static final class SchemaWalk {
        private final Map<Class<?>, PojoDefinition> onPath = new LinkedHashMap<>();
        private final Map<String, PojoDefinition> referenced = new LinkedHashMap<>();
        /** Every class described so far, by the simple name the schema titles it with. */
        private final Map<String, Class<?>> named = new LinkedHashMap<>();
    }

    /**
     * Builds a PojoDefinition structure from a POJO class.
     * Reads both Jackson and LLM annotations to create structured schema.
     *
     * @param pojoClass the class to build definition for
     * @return PojoDefinition object representing the schema
     */
    private static PojoDefinition buildPojoDefinition(Class<?> pojoClass) {
        SchemaWalk walk = new SchemaWalk();
        PojoDefinition root = buildPojoDefinition(pojoClass, walk);
        root.setReferencedDefinitions(walk.referenced);
        return root;
    }

    private static PojoDefinition buildPojoDefinition(Class<?> pojoClass, SchemaWalk walk) {
        // Create the definition with class name and description
        String className = pojoClass.getSimpleName();
        // A schema names a type by its simple name: as the title of its node and as the key
        // of its $defs entry. Two classes sharing one name inside one schema would be told
        // apart by nothing a reader can see, so that is a defect in the types, not a case
        // to paper over with a longer name.
        Class<?> sameName = walk.named.putIfAbsent(className, pojoClass);
        if (sameName != null && sameName != pojoClass) {
            throw new IllegalStateException("Two types named " + className + " in one schema: "
                    + sameName.getName() + " and " + pojoClass.getName() + "; rename one");
        }
        LLMDescription classDesc = pojoClass.getAnnotation(LLMDescription.class);
        String description = classDesc != null ? classDesc.value() : "Schema of " + className;

        PojoDefinition definition = new PojoDefinition(className, description);
        walk.onPath.put(pojoClass, definition);

        // Process all fields including inherited ones
        List<Field> allFields = getAllFields(pojoClass);

        for (Field field : allFields) {
            // Skip static and transient fields
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }

            // Get JSON property name
            String jsonName = getJsonPropertyName(field);
            if (jsonName == null)
                continue; // Skip if @JsonIgnore

            // Build field descriptor
            FieldDescriptor fieldDescriptor = buildFieldDescriptor(field, pojoClass, walk);

            // Add to definition
            definition.addField(jsonName, fieldDescriptor);
        }
        if (Artifact.class.isAssignableFrom(pojoClass)) {
            definition.setArtifact(true);
            // A field declared as the Artifact interface, or as an artifact class that does
            // not extend AbstractArtifact, has no reference field of its own to describe; its
            // reference is described the way every artifact's is.
            if (!definition.getFields().containsKey(ArtifactRegistry.REF_FIELD)) {
                try {
                    definition.addField(ArtifactRegistry.REF_FIELD,
                            buildFieldDescriptor(AbstractArtifact.class.getDeclaredField("artifactRef"), AbstractArtifact.class, walk));
                }
                catch (NoSuchFieldException e) {
                    throw new IllegalStateException("AbstractArtifact no longer declares the artifactRef field its schema is described from", e);
                }
            }
        }

        walk.onPath.remove(pojoClass);
        return definition;
    }

    /**
     * Records that a field came back to a type already being described, and yields the
     * reference that stands in for it. The definition put in the referenced map is the one
     * still being built further up the stack; by the time anything renders it, it is whole.
     */
    private static void referenceInstead(FieldDescriptor descriptor, Class<?> target, SchemaWalk walk) {
        String name = target.getSimpleName();
        walk.referenced.put(name, walk.onPath.get(target));
        descriptor.setReference(name);
    }

    /**
     * Builds a FieldDescriptor for a single field.
     *
     * @param field the field to describe
     * @param contextClass the class containing the field (for generic resolution)
     * @return FieldDescriptor object
     */
    private static FieldDescriptor buildFieldDescriptor(Field field, Class<?> contextClass, SchemaWalk walk) {
        FieldDescriptor descriptor = new FieldDescriptor();

        // Get JSON property name for logging
        String jsonName = getJsonPropertyName(field);

        // Build description from annotations
        StringBuilder descBuilder = new StringBuilder();

        // Add LLM description if present
        LLMDescription desc = field.getAnnotation(LLMDescription.class);
        if (desc != null) {
            descBuilder.append(desc.value());
        }

        // Check if required. The flag is the structure; the token is prose for the model.
        boolean isRequired = field.isAnnotationPresent(LLMRequired.class);
        descriptor.setRequired(isRequired);
        if (isRequired) {
            if (!descBuilder.isEmpty()) descBuilder.append(" ");
            descBuilder.append("(REQUIRED)");
        }

        // Add examples if present
        LLMExample example = field.getAnnotation(LLMExample.class);
        if (example != null && example.value().length > 0) {
            if (!descBuilder.isEmpty()) descBuilder.append(" ");
            descBuilder.append("[Examples: ");
            descBuilder.append(String.join(", ", example.value()));
            descBuilder.append("]");
            descriptor.setExamples(List.of(example.value()));
        }

        // Log warnings for missing descriptions
        if (desc == null) {
            if (isRequired) {
                log.warn("POJO field '{}.{}' is @LLMRequired but missing @LLMDescription - LLM may misunderstand field purpose",
                    contextClass.getSimpleName(), jsonName);
            }
            if (isComplexType(field.getType())) {
                log.info("POJO field '{}.{}' (type: {}) missing @LLMDescription - consider adding for better LLM understanding",
                    contextClass.getSimpleName(), jsonName, field.getType().getSimpleName());
            }
        }

        descriptor.setDescription(!descBuilder.isEmpty() ? descBuilder.toString() : "Field " + jsonName);

        // Resolve generic types if needed
        Type resolvedType = resolveFieldType(field, contextClass);

        // Set type and nested definition based on resolved type
        setTypeAndDefinition(descriptor, resolvedType, walk);
        applyNucleoExtensions(descriptor, field, resolvedType);

        return descriptor;
    }

    /**
     * Records the field's Nucleo annotations as {@code x-nucleo-*} JSON Schema keywords.
     *
     * <p>These are facts about the DATA that a consuming harness needs and cannot derive
     * for itself: how large a field can get and how it may be shortened, whether it belongs
     * in a model's context at all, and what an artifact-typed field points at. A consumer
     * holding our classes reads the annotations directly; one that does not - a process on
     * the far side of an MCP boundary that could not resolve the concrete class - has only
     * the schema. What the consumer then DOES with the fact is its own policy.
     *
     * <p>They are absent from the {@code @}-notation form on purpose: none of this is
     * addressed to a model, and {@code AbstractThinker.buildToolDefinitionBlocks} strips
     * the keywords before a schema is offered to one.
     */
    private static void applyNucleoExtensions(FieldDescriptor descriptor, Field field, Type resolvedType) {
        LLMSummarizable summarizable = field.getAnnotation(LLMSummarizable.class);
        if (summarizable != null) {
            ObjectNode node = NucleoJsonSerializer.createObjectNode();
            node.put("hint", summarizable.value());
            node.put("size", summarizable.size().name());
            node.put("threshold", summarizable.threshold());
            node.put("llmSafe", summarizable.llmSafe());
            node.put("preSummarized", summarizable.preSummarized());
            node.put("staticSummary", summarizable.staticSummary());
            descriptor.putExtension(NucleoSchemaKeywords.SUMMARIZABLE, node);
        }
        if (field.isAnnotationPresent(LLMContextIgnore.class)) {
            descriptor.putExtension(NucleoSchemaKeywords.CONTEXT_IGNORE, BooleanNode.TRUE);
        }
        Class<?> carrier = aliasCarrier(resolvedType);
        if (carrier != null) {
            TypeAlias alias = carrier.getAnnotation(TypeAlias.class);
            if (alias != null) {
                descriptor.putExtension(NucleoSchemaKeywords.TYPE_ALIAS, TextNode.valueOf(alias.value()));
            }
        }
    }

    /**
     * The class whose {@code @TypeAlias} describes this field: the field's own type, or the
     * element type when the field is an array or a collection of them.
     */
    private static Class<?> aliasCarrier(Type resolvedType) {
        if (resolvedType instanceof Class<?> clazz) {
            return clazz.isArray() ? clazz.getComponentType() : clazz;
        }
        if (resolvedType instanceof ParameterizedType pt
                && pt.getRawType() instanceof Class<?> raw
                && (List.class.isAssignableFrom(raw) || Set.class.isAssignableFrom(raw))) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length > 0 && args[0] instanceof Class<?> element) {
                return element;
            }
        }
        return null;
    }

    /**
     * Sets the type and potentially nested definition on a FieldDescriptor based on the resolved type.
     *
     * @param descriptor the descriptor to update
     * @param resolvedType the resolved type of the field
     */
    private static void setTypeAndDefinition(FieldDescriptor descriptor, Type resolvedType, SchemaWalk walk) {
        if (resolvedType instanceof Class) {
            Class<?> clazz = (Class<?>) resolvedType;

            // Primitives and simple types
            if (clazz == String.class) {
                descriptor.setType("string");
            } else if (clazz == Integer.class || clazz == int.class) {
                descriptor.setType("integer");
            } else if (clazz == Long.class || clazz == long.class) {
                descriptor.setType("long");
            } else if (clazz == Double.class || clazz == double.class || clazz == Float.class || clazz == float.class) {
                descriptor.setType("number");
            } else if (clazz == Boolean.class || clazz == boolean.class) {
                descriptor.setType("boolean");
            } else if (clazz == LocalDate.class) {
                descriptor.setType("date");
            } else if (clazz == Date.class || clazz.getName().contains("Date") || clazz.getName().contains("Time")) {
                // Everything temporal that carries a time of day. Told apart from a plain
                // date because the two accept different text, and a consumer that is only
                // told "string" has been told nothing about what will parse.
                descriptor.setType("date-time");
            } else if (clazz.isArray()) {
                // Handle arrays
                Class<?> componentType = clazz.getComponentType();
                if (NucleoJsonSerializer.isComposite(componentType)) {
                    descriptor.setType("array");
                    if (walk.onPath.containsKey(componentType)) {
                        referenceInstead(descriptor, componentType, walk);
                    }
                    else {
                        descriptor.setDefinition(buildPojoDefinition(componentType, walk));
                    }
                } else if (componentType.isEnum()) {
                    descriptor.setType("array of enum");
                    descriptor.setEnumValues(enumWireValues(componentType));
                } else {
                    descriptor.setType("array of " + getSimpleTypeName(componentType));
                }
            } else if (clazz.isEnum()) {
                descriptor.setType("enum");
                descriptor.setEnumValues(enumWireValues(clazz));
            } else if (NucleoJsonSerializer.isComposite(clazz)) {
                // Nested POJO - create nested definition
                descriptor.setType("object");
                if (walk.onPath.containsKey(clazz)) {
                    referenceInstead(descriptor, clazz, walk);
                }
                else {
                    descriptor.setDefinition(buildPojoDefinition(clazz, walk));
                }
            } else if (clazz == Object.class) {
                descriptor.setType("any");
            } else {
                descriptor.setType("object");
            }
        } else if (resolvedType instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) resolvedType;
            Class<?> rawType = (Class<?>) pt.getRawType();
            Type[] typeArgs = pt.getActualTypeArguments();

            if (List.class.isAssignableFrom(rawType) || Set.class.isAssignableFrom(rawType)) {
                // Handle collections
                if (typeArgs.length > 0) {
                    Type elementType = typeArgs[0];
                    if (elementType instanceof Class && NucleoJsonSerializer.isComposite((Class<?>) elementType)) {
                        Class<?> elementClass = (Class<?>) elementType;
                        descriptor.setType("List<" + elementClass.getSimpleName() + ">");
                        if (walk.onPath.containsKey(elementClass)) {
                            referenceInstead(descriptor, elementClass, walk);
                        }
                        else {
                            descriptor.setDefinition(buildPojoDefinition(elementClass, walk));
                        }
                    } else if (elementType instanceof Class<?> elementClass && elementClass.isEnum()) {
                        descriptor.setType("array of enum");
                        descriptor.setEnumValues(enumWireValues(elementClass));
                    } else {
                        descriptor.setType("array of " + describeType(elementType));
                    }
                } else {
                    descriptor.setType("array");
                }
            } else if (Map.class.isAssignableFrom(rawType)) {
                // A map's prose type names both sides for the LLM; its value shape is carried
                // the way a collection's element shape is, so the JSON Schema can state it.
                if (typeArgs.length == 2) {
                    String keyDesc = describeType(typeArgs[0]);
                    String valueDesc = describeType(typeArgs[1]);
                    descriptor.setType("map from " + keyDesc + " to " + valueDesc);
                    if (typeArgs[1] instanceof Class<?> valueClass && NucleoJsonSerializer.isComposite(valueClass)) {
                        if (walk.onPath.containsKey(valueClass)) {
                            referenceInstead(descriptor, valueClass, walk);
                        }
                        else {
                            descriptor.setDefinition(buildPojoDefinition(valueClass, walk));
                        }
                    }
                    else if (typeArgs[1] instanceof Class<?> valueClass && valueClass.isEnum()) {
                        descriptor.setEnumValues(enumWireValues(valueClass));
                    }
                } else {
                    descriptor.setType("map");
                }
            } else {
                descriptor.setType("object");
            }
        } else if (resolvedType instanceof GenericArrayType) {
            GenericArrayType gat = (GenericArrayType) resolvedType;
            Type componentType = gat.getGenericComponentType();
            descriptor.setType("array of " + describeType(componentType));
        } else {
            // Default fallback
            descriptor.setType("object");
        }
    }

    /**
     * The wire values of an enum as the schema publishes them. Public because a refusal that
     * names the accepted values must name exactly the ones the model was shown, which means
     * reading them from here rather than restating them.
     */
    public static List<String> enumWireValues(Class<?> enumClass) {
        List<String> values = new ArrayList<>();
        for (Object constant : enumClass.getEnumConstants()) {
            values.add(NucleoJsonSerializer.valueToTree(constant).asText());
        }
        return values;
    }

    /**
     * Gets a simple type name for common types.
     */
    private static String getSimpleTypeName(Class<?> type) {
        if (type == String.class) return "string";
        if (type == Integer.class || type == int.class) return "integer";
        if (type == Long.class || type == long.class) return "long";
        if (type == Double.class || type == double.class || type == Float.class || type == float.class) return "number";
        if (type == Boolean.class || type == boolean.class) return "boolean";
        return type.getSimpleName();
    }

    /**
     * Gets all fields including inherited ones from superclasses.
     *
     * @param clazz the class to get fields from
     * @return list of all fields including inherited ones
     */
    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
    }

    /**
     * Gets the JSON property name for a field, considering Jackson annotations.
     *
     * @param field the field to check
     * @return JSON property name or null if field should be ignored
     */
    private static String getJsonPropertyName(Field field) {
        // Check for @JsonIgnore
        if (field.isAnnotationPresent(JsonIgnore.class)) {
            return null;
        }

        // Check for @JsonProperty
        JsonProperty jsonProp = field.getAnnotation(JsonProperty.class);
        if (jsonProp != null && !jsonProp.value().isEmpty()) {
            return jsonProp.value();
        }

        // Auto-convert to snake_case - this is the preferred default behavior
        return toSnakeCase(field.getName());
    }

    /**
     * Helper to describe a Type (which might be Class or ParameterizedType)
     */
    private static String describeType(java.lang.reflect.Type type) {
        if (type instanceof Class) {
            return getSimpleTypeName((Class<?>)type);
        }
        else if (type instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType paramType = (java.lang.reflect.ParameterizedType)type;
            if (paramType.getRawType() instanceof Class) {
                Class<?> rawType = (Class<?>)paramType.getRawType();
                if (List.class.isAssignableFrom(rawType) || Set.class.isAssignableFrom(rawType)) {
                    Type[] args = paramType.getActualTypeArguments();
                    if (args.length > 0) {
                        return "array of " + describeType(args[0]);
                    }
                    return "array";
                }
                if (Map.class.isAssignableFrom(rawType)) {
                    Type[] args = paramType.getActualTypeArguments();
                    if (args.length == 2) {
                        return "map from " + describeType(args[0]) + " to " + describeType(args[1]);
                    }
                    return "map";
                }
                return rawType.getSimpleName();
            }
        }
        return "any";
    }


    /**
     * Validates that required fields are present in a POJO instance.
     * This is a simple validation that only checks @LLMRequired fields.
     * For detailed error information, use getValidationErrors().
     *
     * @param pojo the object to validate
     * @return true if all required fields are valid
     */
    public static <T > boolean validateRequiredFields(T pojo) {
        // Use the detailed validation method and check if any errors exist
        return validationErrors(pojo).isEmpty();
    }

    /**
     * Gets a list of validation errors for a POJO instance.
     * Useful for debugging why validation failed.
     *
     * @param pojo the object to validate
     * @return list of error messages, empty if valid
     */
    public static <T > List<String> validationErrors(T pojo) {
        List<String> errors = new ArrayList<>();

        if (pojo == null) {
            errors.add("Object is null");
            return errors;
        }

        Class<?> clazz = pojo.getClass();
        // Inheritance-aware walk, same as schema generation - @LLMRequired fields
        // declared on superclasses must validate too.
        List<Field> fields = getAllFields(clazz);

        for (Field field : fields) {
            if (field.isAnnotationPresent(LLMRequired.class)) {
                field.setAccessible(true);
                try {
                    Object value = field.get(pojo);
                    String fieldName = getJsonPropertyName(field);

                    String fieldType = field.getType().getSimpleName();
                    String genericType = getGenericTypeInfo(field);

                    String className = clazz.getSimpleName();

                    if (value == null) {
                        errors.add(String.format("[%s] Required field '%s' (type: %s%s) is null - expected non-null value", className, fieldName, fieldType, genericType));
                    }
                    else if (value instanceof String && ((String)value).trim().isEmpty()) {
                        errors.add(String.format("[%s] Required field '%s' (type: %s) is blank - expected non-empty string, received: '%s'", className, fieldName, fieldType, value));
                    }
                    // Note: Empty collections are valid - they mean "no items found"
                    // Valid scenarios: "extracts": [] when no relevant extracts exist
                }
                catch (IllegalAccessException e) {
                    errors.add(String.format("[%s] Cannot access required field '%s' (type: %s) - reflection error: %s", clazz.getSimpleName(), getJsonPropertyName(field), field.getType()
                                                                                                                                                                                 .getSimpleName(), e.getMessage()));
                    log.error(e.getMessage(), e);
                }
            }
        }

        return errors;
    }

    /**
     * Gets generic type information for better error messages.
     */
    private static String getGenericTypeInfo(Field field) {
        if (field.getGenericType() instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType paramType = (java.lang.reflect.ParameterizedType)field.getGenericType();
            java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
            if (typeArgs.length > 0) {
                StringBuilder sb = new StringBuilder("<");
                for (int i = 0; i < typeArgs.length; i++) {
                    if (i > 0)
                        sb.append(", ");
                    if (typeArgs[i] instanceof Class) {
                        sb.append(((Class<?>)typeArgs[i]).getSimpleName());
                    }
                    else {
                        sb.append(typeArgs[i].toString());
                    }
                }
                sb.append(">");
                return sb.toString();
            }
        }
        return "";
    }

    /**
     * Converts camelCase field names to snake_case for JSON properties.
     * This is the DEFAULT behavior - no annotation needed for this conversion.
     * Examples: "relevanceScore" -> "relevance_score", "documentType" -> "document_type"
     */
    private static String toSnakeCase(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return camelCase;
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                result.append('_');
            }
            result.append(Character.toLowerCase(c));
        }
        return result.toString();
    }

    /**
     * Determines if a type is complex and would benefit from having an @LLMDescription.
     */
    private static boolean isComplexType(Class<?> type) {
        // Collections and arrays are complex
        if (java.util.Collection.class.isAssignableFrom(type) || type.isArray()) {
            return true;
        }
        // Maps are complex
        if (java.util.Map.class.isAssignableFrom(type)) {
            return true;
        }
        // Custom objects (not primitives, wrappers, or common types)
        return !isPrimitiveOrCommonType(type);
    }

    /**
     * Checks if a type is a primitive, wrapper, or commonly understood type.
     */
    private static boolean isPrimitiveOrCommonType(Class<?> type) {
        return type.isPrimitive() || type == String.class || type == Integer.class || type == Long.class || type == Double.class || type == Float.class
               || type == Boolean.class || type == Character.class || type == java.time.LocalDate.class || type == java.time.LocalDateTime.class
               || type == java.time.Instant.class || type == java.util.Date.class;
    }

    /**
     * Resolves the actual type of a field, handling generic type parameters.
     * For fields with type variables (e.g., T, R), resolves them to concrete types
     * by walking the inheritance chain.
     *
     * @param field        the field to resolve
     * @param contextClass the concrete class containing the field (directly or through inheritance)
     * @return the resolved Type, never null
     * @throws IllegalStateException if type cannot be resolved
     */
    private static Type resolveFieldType(Field field, Class<?> contextClass) {
        Type genericType = field.getGenericType();

        // If it's not a type variable or parameterized type with variables, return as-is
        if (!containsTypeVariable(genericType)) {
            return genericType;
        }

        // Check if this is an unbound type parameter of the class itself
        if (genericType instanceof TypeVariable) {
            TypeVariable<?> tv = (TypeVariable<?>)genericType;
            TypeVariable<?>[] contextTypeParams = contextClass.getTypeParameters();
            for (TypeVariable<?> param : contextTypeParams) {
                if (param.getName().equals(tv.getName())) {
                    // This is an unbound type parameter - return Object.class as fallback
                    // This allows specialized handlers to replace with concrete types at runtime
                    return Object.class;
                }
            }
        }

        // Build type variable mappings from the context class
        Map<String, Type> typeBindings = buildTypeBindings(contextClass, field.getDeclaringClass());

        // Resolve the type using the bindings
        Type resolved = resolveType(genericType, typeBindings);

        // If we still have unresolved type variables, return Object.class as fallback
        // This happens for generic classes like ThinkingResponse<O> where O is unbound
        // The ThinkingResponseHandler will provide the concrete type at runtime
        if (containsTypeVariable(resolved)) {
            String fieldName = getJsonPropertyName(field);
            log.debug("Cannot resolve generic type for field '{}' in class {}. Using 'any' type for unresolved: {}",
                fieldName, contextClass.getSimpleName(), resolved);
            return Object.class;
        }

        return resolved;
    }

    /**
     * Checks if a Type contains any TypeVariables.
     */
    private static boolean containsTypeVariable(Type type) {
        if (type instanceof TypeVariable) {
            return true;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType)type;
            for (Type arg : pt.getActualTypeArguments()) {
                if (containsTypeVariable(arg)) {
                    return true;
                }
            }
        }
        if (type instanceof GenericArrayType) {
            GenericArrayType gat = (GenericArrayType)type;
            return containsTypeVariable(gat.getGenericComponentType());
        }
        if (type instanceof WildcardType) {
            WildcardType wt = (WildcardType)type;
            for (Type bound : wt.getUpperBounds()) {
                if (containsTypeVariable(bound)) {
                    return true;
                }
            }
            for (Type bound : wt.getLowerBounds()) {
                if (containsTypeVariable(bound)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Builds a map of type variable names to their concrete bindings by walking
     * the inheritance chain from contextClass to targetClass.
     *
     * @param contextClass the concrete class we're generating schema for
     * @param targetClass  the class that declares the field (may be a superclass)
     * @return map of type variable names to their resolved types
     */
    private static Map<String, Type> buildTypeBindings(Class<?> contextClass, Class<?> targetClass) {
        Map<String, Type> bindings = new HashMap<>();

        // Walk up the inheritance chain
        Class<?> current = contextClass;
        while (current != null && current != Object.class) {
            Type genericSuper = current.getGenericSuperclass();

            if (genericSuper instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType)genericSuper;
                Class<?> rawSuper = (Class<?>)pt.getRawType();

                // Get the type parameters of the superclass
                TypeVariable<?>[] superParams = rawSuper.getTypeParameters();
                Type[] actualArgs = pt.getActualTypeArguments();

                // Map type parameters to their actual arguments
                for (int i = 0; i < superParams.length; i++) {
                    String paramName = superParams[i].getName();
                    Type actualType = actualArgs[i];

                    // If the actual type is itself a type variable, resolve it from existing bindings
                    if (actualType instanceof TypeVariable) {
                        String varName = ((TypeVariable<?>)actualType).getName();
                        if (bindings.containsKey(varName)) {
                            actualType = bindings.get(varName);
                        }
                    }

                    bindings.put(paramName, actualType);
                }

                // If we've reached the target class, we're done
                if (rawSuper == targetClass) {
                    break;
                }

                current = rawSuper;
            }
            else if (genericSuper instanceof Class) {
                // Non-parameterized superclass
                current = (Class<?>)genericSuper;
                if (current == targetClass) {
                    break;
                }
            }
            else {
                break;
            }
        }

        return bindings;
    }

    /**
     * Resolves a Type by substituting TypeVariables with their concrete bindings.
     *
     * @param type     the type to resolve
     * @param bindings map of type variable names to concrete types
     * @return the resolved type
     */
    private static Type resolveType(Type type, Map<String, Type> bindings) {
        if (type instanceof TypeVariable) {
            TypeVariable<?> tv = (TypeVariable<?>)type;
            Type resolved = bindings.get(tv.getName());
            if (resolved != null) {
                // Recursively resolve in case the binding is itself a type that needs resolution
                return resolveType(resolved, bindings);
            }
            return type; // Unresolved
        }

        if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType)type;
            Type[] args = pt.getActualTypeArguments();
            Type[] resolvedArgs = new Type[args.length];
            boolean changed = false;

            for (int i = 0; i < args.length; i++) {
                resolvedArgs[i] = resolveType(args[i], bindings);
                if (resolvedArgs[i] != args[i]) {
                    changed = true;
                }
            }

            if (changed) {
                // Create a new ParameterizedType with resolved arguments
                return new ResolvedParameterizedType(pt.getRawType(), resolvedArgs, pt.getOwnerType());
            }
            return type;
        }

        if (type instanceof GenericArrayType) {
            GenericArrayType gat = (GenericArrayType)type;
            Type componentType = resolveType(gat.getGenericComponentType(), bindings);
            if (componentType != gat.getGenericComponentType()) {
                return new ResolvedGenericArrayType(componentType);
            }
        }

        return type;
    }

    /**
     * A ParameterizedType implementation for resolved types.
     */
    private static class ResolvedParameterizedType implements ParameterizedType {
        private final Type rawType;
        private final Type[] actualTypeArguments;
        private final Type ownerType;

        ResolvedParameterizedType(Type rawType, Type[] actualTypeArguments, Type ownerType) {
            this.rawType = rawType;
            this.actualTypeArguments = actualTypeArguments;
            this.ownerType = ownerType;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return actualTypeArguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (ownerType != null) {
                sb.append(ownerType).append(".");
            }
            sb.append(rawType);
            if (actualTypeArguments != null && actualTypeArguments.length > 0) {
                sb.append("<");
                for (int i = 0; i < actualTypeArguments.length; i++) {
                    if (i > 0)
                        sb.append(", ");
                    sb.append(actualTypeArguments[i]);
                }
                sb.append(">");
            }
            return sb.toString();
        }
    }


    /**
     * A GenericArrayType implementation for resolved array types.
     */
    private static class ResolvedGenericArrayType implements GenericArrayType {
        private final Type componentType;

        ResolvedGenericArrayType(Type componentType) {
            this.componentType = componentType;
        }

        @Override
        public Type getGenericComponentType() {
            return componentType;
        }

        @Override
        public String toString() {
            return componentType + "[]";
        }
    }


}