/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.annotations.*;
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
import java.util.*;

/**
 * {@link ToolProvider} that wraps a {@code Class<? extends Tool>}. Reads
 * {@link ToolName}, {@link ToolDescription}, {@link DisplayName}, {@link ToolWeight}
 * annotations. Its name and its input and output types are the code's alone. Its
 * description, its read-only flag, its display name, its weight and its schemas are read
 * through {@link Annotations}, so a deployment's store can override them: they are generated
 * once via {@link PojoResponseHandler} and kept until {@link Annotations#changed()} says the
 * store changed.
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
    private final Class<?> inputType;
    private final Class<?> outputType;
    private volatile Described described;

    /**
     * Everything of the tool that is read from annotations a deployment can override, as it
     * read under one {@link Annotations#generation() generation}.
     */
    private static final class Described {
        private long generation;
        private String description;
        private boolean readOnly;
        private String displayName;
        private String actionVerb;
        private ToolWeight weight;
        private String schemaJson;
        private String outputSchemaJson;
    }

    private ClassToolProvider(Class<? extends Tool> toolClass) {
        this.toolClass = toolClass;
        ToolName nameAnn = toolClass.getAnnotation(ToolName.class);
        if (nameAnn == null) {
            throw new IllegalArgumentException(
                    "Tool class " + toolClass.getName() + " is missing @ToolName annotation");
        }
        this.name = nameAnn.value();
        this.inputType = resolveInputType(toolClass);
        this.outputType = resolveTypeArgument(toolClass, 1);
        // Described here and not at the first question, so that a tool that cannot be
        // described is refused where it is registered
        this.described = describe(Annotations.generation());
    }

    /** The tool as it reads now: described again when the deployment's store has changed. */
    private Described described() {
        Described held = described;
        long generation = Annotations.generation();
        if (held.generation != generation) {
            held = describe(generation);
            described = held;
        }
        return held;
    }

    private Described describe(long generation) {
        Described fresh = new Described();
        fresh.generation = generation;
        ResolvedAnnotation<ToolDescription> descAnn = Annotations.of(toolClass, ToolDescription.class);
        String text = descAnn != null ? descAnn.text() : null;
        fresh.description = text != null ? text : "Tool: " + name;
        fresh.readOnly = descAnn != null && descAnn.bool("readOnly");
        ResolvedAnnotation<DisplayName> displayAnn = Annotations.of(toolClass, DisplayName.class);
        String shown = displayAnn != null ? displayAnn.string("value") : null;
        fresh.displayName = shown != null ? shown : toolClass.getSimpleName();
        fresh.actionVerb = displayAnn != null ? displayAnn.string("action") : "";
        fresh.weight = resolveWeight(toolClass);
        fresh.schemaJson = refine(toolClass, this.inputType, generateSchemaJson(this.inputType, name));
        // Only a composite has a schema worth publishing. The generator describes POJOs and
        // writes "type": "object" for whatever it is handed, so asking it about a String
        // would produce an object schema for a bare string - a claim no result can meet.
        // And only an output the generator can describe truthfully is published at all: a
        // ListArtifact-typed field is a parameterized composite the walker cannot resolve, so
        // its schema degrades to a string while the wire ships the full bean, and a published
        // schema the result cannot validate against fails every call at the SDK. Until the
        // walker resolves type arguments, such an output ships as text content only.
        fresh.outputSchemaJson = NucleoJsonSerializer.isComposite(this.outputType)
                        && describableForWire(this.outputType, new java.util.HashSet<>())
                ? generateSchemaJson(this.outputType, name)
                : null;
        return fresh;
    }

    /**
     * Every annotation of this tool a deployment can override, with what the code declares:
     * those of the tool class and of every class its input and output are described by.
     */
    public List<AnnotationKey> annotationKeys() {
        Map<String, AnnotationKey> keys = new LinkedHashMap<>();
        Set<Class<?>> classes = new LinkedHashSet<>();
        classes.add(toolClass);
        classes.addAll(PojoResponseHandler.describedClasses(inputType));
        if (NucleoJsonSerializer.isComposite(outputType)) {
            classes.addAll(PojoResponseHandler.describedClasses(outputType));
        }
        for (Class<?> described : classes) {
            for (AnnotationKey key : Annotations.keysOf(described)) {
                keys.putIfAbsent(key.key(), key);
            }
        }
        return new ArrayList<>(keys.values());
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
        ResolvedAnnotation<ToolWeight> ann = Annotations.of(cls, ToolWeight.class);
        if (ann != null) {
            return ann.overridden() ? new StoredToolWeight(ann) : ann.declared();
        }
        if (Thinker.class.isAssignableFrom(cls)) {
            return DefaultToolWeights.THINKER_DEFAULT;
        }
        return DefaultToolWeights.API_CALL_DEFAULT;
    }

    /** A weight whose attributes the deployment's store had a say in, read once. */
    private static final class StoredToolWeight implements ToolWeight {
        private final ToolType type;
        private final int level;
        private final int min;
        private final int max;

        private StoredToolWeight(ResolvedAnnotation<ToolWeight> resolved) {
            this.type = resolved.enumValue("type", ToolType.class);
            this.level = resolved.integer("level");
            this.min = resolved.integer("min");
            this.max = resolved.integer("max");
        }

        @Override
        public ToolType type() {
            return type;
        }

        @Override
        public int level() {
            return level;
        }

        @Override
        public int min() {
            return min;
        }

        @Override
        public int max() {
            return max;
        }

        @Override
        public Class<? extends java.lang.annotation.Annotation> annotationType() {
            return ToolWeight.class;
        }
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
        return rawClassOf(boundAtTool(cls, Map.of(), Tool.class.getTypeParameters()[index]));
    }

    /**
     * What one of {@code Tool}'s own type variables is bound to, seen from a class of the
     * tool's hierarchy. The classes between a tool and {@code Tool} may declare any number of
     * type parameters in any order, so nothing is read by position on the way: each step up
     * pairs the parent's variables with the arguments the child gave them, an argument that
     * is the child's own variable being replaced by what that variable was bound to below.
     * Only at {@code Tool} itself is the wanted variable looked up.
     *
     * @param current  the class reached so far
     * @param bindings what the type variables of {@code current} are bound to, by the classes below it
     * @param wanted   a type variable of {@code Tool}
     * @return the type bound to it, or null when the hierarchy leaves it open
     */
    private static Type boundAtTool(Class<?> current, Map<TypeVariable<?>, Type> bindings, TypeVariable<?> wanted) {
        if (current == Tool.class) {
            return bindings.get(wanted);
        }
        List<Type> parents = new ArrayList<>();
        if (current.getGenericSuperclass() != null) {
            parents.add(current.getGenericSuperclass());
        }
        parents.addAll(Arrays.asList(current.getGenericInterfaces()));
        for (Type parent : parents) {
            Class<?> raw = rawClassOf(parent);
            if (raw == null || !Tool.class.isAssignableFrom(raw)) {
                continue;
            }
            Map<TypeVariable<?>, Type> parentBindings = new HashMap<>();
            if (parent instanceof ParameterizedType parameterized) {
                TypeVariable<?>[] variables = raw.getTypeParameters();
                Type[] arguments = parameterized.getActualTypeArguments();
                for (int i = 0; i < variables.length; i++) {
                    // A variable of the current class stands for whatever was bound to it below;
                    // one that nothing bound stays out, and is open at Tool too
                    Type argument = arguments[i] instanceof TypeVariable<?> variable ? bindings.get(variable) : arguments[i];
                    if (argument != null) {
                        parentBindings.put(variables[i], argument);
                    }
                }
            }
            Type bound = boundAtTool(raw, parentBindings, wanted);
            if (bound != null) {
                return bound;
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
        // Nor is a stored override its annotation cannot take: that is the deployment's
        // store to correct, and a schema quietly missing would hide which row is wrong.
        catch (AnnotationOverrideException e) {
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
        return described().description;
    }

    @Override
    public String schemaJson() {
        return described().schemaJson;
    }

    @Override
    public ToolWeight weight() {
        return described().weight;
    }

    @Override
    public String displayName() {
        return described().displayName;
    }

    @Override
    public String actionVerb() {
        return described().actionVerb;
    }

    @Override
    public boolean readOnly() {
        return described().readOnly;
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
        return described().outputSchemaJson;
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
        Object input;
        try {
            input = NucleoJsonSerializer.convert(raw, inputType);
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
        // The schema marks a field required; a call that leaves it out or blank is refused
        // here, by the field's name, before the tool runs on a value it was promised never
        // to see
        List<String> missing = PojoResponseHandler.validationErrors(input);
        if (!missing.isEmpty()) {
            throw new InvalidInputException(name, inputType.getSimpleName(), String.join("; ", missing));
        }
        return input;
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
