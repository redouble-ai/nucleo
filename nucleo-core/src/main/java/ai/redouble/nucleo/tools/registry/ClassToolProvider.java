/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.slf4j.*;

import java.io.*;
import java.lang.reflect.*;

/**
 * {@link ToolProvider} that wraps a {@code Class<? extends Tool>}. Reads
 * {@link ToolName}, {@link ToolDescription}, {@link DisplayName}, {@link ToolWeight}
 * annotations. Schema is generated once via {@link PojoResponseHandler} and cached.
 *
 * <p>Equality is by wrapped class identity, so registering the same class twice produces
 * the same provider key, supporting {@link ToolRegistry}'s upsert-on-same-identity policy.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-05-08)
 */
public final class ClassToolProvider implements ToolProvider {
    private static final Logger log = LoggerFactory.getLogger(ClassToolProvider.class);

    private final Class<? extends Tool> toolClass;
    private final String name;
    private final String description;
    private final String displayName;
    private final String actionVerb;
    private final ToolWeight weight;
    private final Class<?> inputType;
    private final String schemaJson;
    private final Class<?> outputType;
    private final String outputSchemaJson;
    private final boolean readOnly;

    private ClassToolProvider(Class<? extends Tool> toolClass) {
        this.toolClass = toolClass;
        ToolName nameAnn = toolClass.getAnnotation(ToolName.class);
        if (nameAnn == null) {
            throw new IllegalArgumentException(
                    "Tool class " + toolClass.getName() + " is missing @ToolName annotation");
        }
        this.name = nameAnn.value();
        ToolDescription descAnn = toolClass.getAnnotation(ToolDescription.class);
        this.description = descAnn != null ? descAnn.value() : "Tool: " + name;
        this.readOnly = descAnn != null && descAnn.readOnly();
        DisplayName displayAnn = toolClass.getAnnotation(DisplayName.class);
        this.displayName = displayAnn != null ? displayAnn.value() : toolClass.getSimpleName();
        this.actionVerb = displayAnn != null ? displayAnn.action() : "";
        this.weight = resolveWeight(toolClass);
        this.inputType = resolveInputType(toolClass);
        this.schemaJson = refine(toolClass, this.inputType, generateSchemaJson(this.inputType, name));
        this.outputType = resolveTypeArgument(toolClass, 1);
        // Only a composite has a schema worth publishing. The generator describes POJOs and
        // writes "type": "object" for whatever it is handed, so asking it about a String
        // would produce an object schema for a bare string - a claim no result can meet.
        // And only an output the generator can describe truthfully is published at all: a
        // ListArtifact-typed field is a parameterized composite the walker cannot resolve, so
        // its schema degrades to a string while the wire ships the full bean, and a published
        // schema the result cannot validate against fails every call at the SDK. Until the
        // walker resolves type arguments, such an output ships as text content only.
        this.outputSchemaJson = NucleoJsonSerializer.isComposite(this.outputType)
                        && describableForWire(this.outputType, new java.util.HashSet<>())
                ? generateSchemaJson(this.outputType, name)
                : null;
    }

    /**
     * Whether the generator's schema for this type matches what full-form serialization puts
     * on the wire. It does not wherever a {@code ListArtifact} field appears: the walker has
     * no generic resolution, so that field's schema collapses to a string while the wire
     * carries the whole list.
     */
    private static boolean describableForWire(Class<?> type, java.util.Set<Class<?>> visited) {
        if (!visited.add(type)) {
            return true;
        }
        for (Class<?> walked = type; walked != null && walked != Object.class; walked = walked.getSuperclass()) {
            for (java.lang.reflect.Field field : walked.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (ai.redouble.nucleo.harness.artifacts.ListArtifact.class.isAssignableFrom(field.getType())) {
                    return false;
                }
                if (NucleoJsonSerializer.isComposite(field.getType()) && !describableForWire(field.getType(), visited)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Wraps a tool class. Constructs a fresh provider each call; the schema generation it
     * pays for happens once per instance, and {@link ToolRegistry}'s same-identity upsert
     * keeps repeated registrations from stacking.
     */
    public static ClassToolProvider of(Class<? extends Tool> toolClass) {
        return new ClassToolProvider(toolClass);
    }

    private static ToolWeight resolveWeight(Class<? extends Tool> cls) {
        ToolWeight ann = cls.getAnnotation(ToolWeight.class);
        if (ann != null) {
            return ann;
        }
        if (Thinker.class.isAssignableFrom(cls)) {
            return DefaultToolWeights.THINKER_DEFAULT;
        }
        return DefaultToolWeights.API_CALL_DEFAULT;
    }

    private static Class<?> resolveInputType(Class<? extends Tool> cls) {
        Class<?> resolved = resolveTypeArgument(cls, 0);
        if (resolved == null) {
            throw new IllegalArgumentException(
                    "Cannot determine input type for tool: " + cls.getSimpleName());
        }
        return resolved;
    }

    /**
     * The raw class behind one of {@code Tool<I,O>}'s type arguments: 0 for the input,
     * 1 for the output. Null when the tool does not declare it concretely, which is a
     * fatal omission for the input and a silent one for the output - a tool whose output
     * type does not resolve simply publishes no output schema.
     */
    private static Class<?> resolveTypeArgument(Class<? extends Tool> cls, int index) {
        // The declaration may sit any number of levels up: a family of tools shares one generic
        // base that fixes the arguments, and a member of the family extends that base raw. The
        // first parameterized ancestor is the one that fixed them.
        for (Class<?> current = cls; current != null && current != Object.class; current = current.getSuperclass()) {
            Type superclass = current.getGenericSuperclass();
            if (superclass instanceof ParameterizedType pType) {
                Type[] typeArgs = pType.getActualTypeArguments();
                if (typeArgs.length > index) {
                    Class<?> raw = rawClassOf(typeArgs[index]);
                    if (raw != null) {
                        return raw;
                    }
                }
            }
        }
        for (Type type : cls.getGenericInterfaces()) {
            if (type instanceof ParameterizedType pType) {
                if (Tool.class.isAssignableFrom((Class<?>) pType.getRawType())) {
                    Type[] typeArgs = pType.getActualTypeArguments();
                    if (typeArgs.length > index) {
                        Class<?> raw = rawClassOf(typeArgs[index]);
                        if (raw != null) {
                            return raw;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Raw class of a declared input type: handles both plain classes and
     * parameterized inputs like {@code BatchInput<S>}, whose raw class is
     * what schema generation and input parsing operate on (the type parameter is
     * erased anyway).
     */
    private static Class<?> rawClassOf(Type type) {
        if (type instanceof Class<?> typed) {
            return typed;
        }
        if (type instanceof ParameterizedType pType && pType.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return null;
    }

    /**
     * The schema narrowed by the tool's {@link SchemaRefinedBy refiner}, when it declares one.
     * A refiner that cannot be constructed or cannot read the schema is a defect of the tool,
     * and a tool whose refinement failed would publish a schema that invites the guessing the
     * refiner exists to end - so it throws rather than degrading.
     */
    private static String refine(Class<? extends Tool> toolClass, Class<?> inputType, String generated) {
        SchemaRefinedBy declared = toolClass.getAnnotation(SchemaRefinedBy.class);
        if (declared == null || generated == null) {
            return generated;
        }
        try {
            SchemaRefiner refiner = declared.value().getDeclaredConstructor().newInstance();
            ObjectNode schema = (ObjectNode)NucleoJsonSerializer.readTree(generated);
            refiner.refine(inputType, schema);
            return schema.toPrettyString();
        }
        catch (ReflectiveOperationException | IOException e) {
            throw new IllegalStateException("Tool " + toolClass.getName() + " declares schema refiner "
                    + declared.value().getName() + " which could not refine its input schema", e);
        }
    }

    private static String generateSchemaJson(Class<?> inputType, String toolName) {
        if (inputType == null) {
            return null;
        }
        try {
            PojoResponseHandler<?> handler = new PojoResponseHandler<>(inputType);
            return handler.writeDefinition().toJsonSchema();
        }
        // Throwable, not Exception, and the difference is not academic: a schema walker that
        // failed to terminate raised a StackOverflowError, which an Exception catch cannot
        // see, so the failure escaped this constructor and made every thinker whose palette
        // held the tool impossible to construct. A schema this process could not generate is
        // its own bug and says so at ERROR, but it degrades to a tool published without one
        // rather than taking agents down with it. An OutOfMemoryError is not a degradation -
        // the JVM is in trouble and swallowing it would hide that - so it propagates.
        catch (OutOfMemoryError e) {
            throw e;
        }
        catch (Throwable t) {
            log.error("Failed to generate schema for tool {}; it is published without one", toolName, t);
            return null;
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String schemaJson() {
        return schemaJson;
    }

    @Override
    public ToolWeight weight() {
        return weight;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public String actionVerb() {
        return actionVerb;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public Class<?> inputType() {
        return inputType;
    }

    @Override
    public Class<?> outputType() {
        return outputType;
    }

    @Override
    public String outputSchemaJson() {
        return outputSchemaJson;
    }

    @Override
    public Class<? extends Tool> toolClass() {
        return toolClass;
    }

    @Override
    public Object parseInput(JsonNode raw) throws CorrectableLLMException {
        if (raw == null) {
            throw new InvalidInputException(name, "null", "input is null");
        }
        try {
            return NucleoJsonSerializer.convert(raw, inputType);
        }
        catch (Exception e) {
            // The parser's complaint quotes what it was given - Jackson names the offending
            // value, java.time prints the text it could not parse - so it goes to the log and
            // stays on the CAUSE, never in the message a model or a prompt reads. Its PATH is
            // a different thing: those are our own field names from our own POJO, so naming
            // them is what lets a caller find the field to fix without us repeating anything
            // the caller wrote.
            log.warn("Tool {} could not parse its input as {}: {}", name, inputType.getSimpleName(), e.getMessage());
            String field = offendingField(e);
            throw new InvalidInputException(field != null ? field : name, inputType.getSimpleName(),
                    "the value does not fit the type declared for it", e);
        }
    }

    /**
     * The dotted path of the field Jackson could not map, from the mapping exception's own
     * reference chain, or null when the failure names no field.
     */
    private static String offendingField(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
                StringBuilder path = new StringBuilder();
                for (JsonMappingException.Reference reference : mapping.getPath()) {
                    if (reference.getFieldName() == null) {
                        continue;
                    }
                    if (!path.isEmpty()) {
                        path.append('.');
                    }
                    path.append(reference.getFieldName());
                }
                if (!path.isEmpty()) {
                    return path.toString();
                }
            }
        }
        return null;
    }

    @Override
    public Tool<?, ?> create(Identifiable parent) throws LLMReadableCheckedException {
        try {
            Constructor<? extends Tool> ctor = toolClass.getDeclaredConstructor(Identifiable.class);
            Tool<?, ?> tool = ctor.newInstance(parent);
            if (tool instanceof ModelDependent dep) {
                if (dep.getGrade() == null && parent instanceof ModelDependent parentDep) {
                    dep.setGrade(parentDep.getGrade());
                }
            }
            return tool;
        }
        catch (NoSuchMethodException e) {
            throw new SystemException("ClassToolProvider",
                    "Tool " + toolClass.getSimpleName() + " must declare constructor "
                            + toolClass.getSimpleName() + "(Identifiable parent)", e);
        }
        catch (Exception e) {
            // Reflection wraps whatever the constructor threw, and the wrapper's own message
            // is null - which is all the report said when a tool constructor overflowed the
            // stack. Name the cause instead, so the next failure of this kind identifies
            // itself without anyone reading a stack trace.
            Throwable cause = e instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause()
                    : e;
            String detail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            throw new SystemException("ClassToolProvider",
                    "Failed to instantiate tool " + name + ": " + detail, cause);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClassToolProvider other && other.toolClass.equals(this.toolClass);
    }

    @Override
    public int hashCode() {
        return toolClass.hashCode();
    }

    @Override
    public String toString() {
        return "ClassToolProvider[" + name + "]";
    }
}
