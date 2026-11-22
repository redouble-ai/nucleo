/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.annotations;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.node.*;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Reads an annotation the way a deployment wants it read: from the deployment's
 * {@link AnnotationResolver} when it holds something for the annotation's key, from the code
 * otherwise. With no resolver set every read is the code's.
 *
 * <p>Whatever describes a tool or a type to a model is read through here, so one stored row
 * changes what a thinker, a tool lookup and an MCP client are told, and no class is edited.
 *
 * <p>The code decides what exists and the store only restates it. An annotation the code
 * does not carry is not there, whatever a store holds under its key: the store is not asked
 * for it and cannot add it, so nothing outside the code can describe a class into a tool or
 * give an element an annotation its author did not.
 *
 * <p>What a resolver answered is kept, per class, until {@link #changed()} is called. A
 * deployment calls it when its store changes; everything built from an annotation carries
 * the {@link #generation()} it was built under and is built again after that.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-22)
 */
public final class Annotations {
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final ConcurrentHashMap<Class<?>, Held> HELD = new ConcurrentHashMap<>();
    /** The annotations a class's keys are gathered for; one read through here adds its own. */
    private static final Set<Class<? extends Annotation>> KNOWN = ConcurrentHashMap.newKeySet();
    private static volatile AnnotationResolver resolver;

    static {
        KNOWN.add(LLMDescription.class);
        KNOWN.add(LLMExample.class);
        KNOWN.add(DisplayName.class);
    }

    private Annotations() {
    }

    /** What one class's keys were answered with, under one generation. */
    private static final class Held {
        private final long generation;
        private final Set<String> asked = new HashSet<>();
        private final Map<String, AnnotationOverride> found = new HashMap<>();

        private Held(long generation) {
            this.generation = generation;
        }
    }

    /** Sets the deployment's store. What an earlier resolver answered is dropped. */
    public static void setResolver(AnnotationResolver resolver) {
        if (resolver == null) {
            throw new IllegalArgumentException("resolver is null; clearResolver() removes one");
        }
        Annotations.resolver = resolver;
        changed();
    }

    /** Removes the deployment's store: every annotation reads as the code wrote it. */
    public static void clearResolver() {
        Annotations.resolver = null;
        changed();
    }

    /** The resolver in use, null when none is set. */
    public static AnnotationResolver getResolver() {
        return resolver;
    }

    /**
     * Tells Nucleo the store changed. Everything the resolver answered is dropped and asked
     * for again when next needed, and {@link #generation()} moves on.
     */
    public static void changed() {
        GENERATION.incrementAndGet();
        HELD.clear();
    }

    /**
     * A number that moves whenever a read through here may answer differently than before.
     * What was built from annotations is current while this is what it was at the build.
     */
    public static long generation() {
        return GENERATION.get();
    }

    /** The key of an annotation on a class: {@code <class name>@<Annotation>}. */
    public static String keyOf(Class<?> owner, Class<? extends Annotation> type) {
        return owner.getName() + "@" + type.getSimpleName();
    }

    /** The key of an annotation on a field: {@code <class name>.<field>@<Annotation>}. */
    public static String keyOf(Class<?> owner, String field, Class<? extends Annotation> type) {
        return owner.getName() + "." + field + "@" + type.getSimpleName();
    }

    /**
     * An annotation of a class.
     *
     * @return the annotation as it reads, or null when the code carries none there
     * @throws AnnotationOverrideException when what is stored cannot be taken by the annotation
     */
    public static <A extends Annotation> ResolvedAnnotation<A> of(Class<?> owner, Class<A> type) {
        KNOWN.add(type);
        return resolved(owner, List.of(keyOf(owner, type)), type, owner.getAnnotation(type));
    }

    /**
     * An annotation of a field, seen from the class a definition is being built for. The
     * store is asked under that class first and under the class that declares the field
     * second, so a field many classes inherit is described once and reworded for one of them
     * where needed. The first key that holds something supplies the whole override.
     *
     * @param builtFor the class being described, which declares or inherits the field
     * @return the annotation as it reads, or null when the code carries none there
     * @throws AnnotationOverrideException when what is stored cannot be taken by the annotation
     */
    public static <A extends Annotation> ResolvedAnnotation<A> of(Class<?> builtFor, Field field, Class<A> type) {
        KNOWN.add(type);
        List<String> keys = new ArrayList<>(2);
        keys.add(keyOf(builtFor, field.getName(), type));
        if (field.getDeclaringClass() != builtFor) {
            keys.add(keyOf(field.getDeclaringClass(), field.getName(), type));
        }
        return resolved(builtFor, keys, type, field.getAnnotation(type));
    }

    /**
     * Every annotation of a class and of its fields that a deployment can override, with
     * what the code declares: the listing an editing screen shows. Only an annotation the
     * code carries is listed, since only that can be overridden. A field is listed under the
     * class that declares it.
     */
    public static List<AnnotationKey> keysOf(Class<?> owner) {
        List<Class<? extends Annotation>> types = new ArrayList<>(KNOWN);
        types.sort(Comparator.comparing(Class::getSimpleName));
        List<AnnotationKey> keys = new ArrayList<>();
        for (Class<? extends Annotation> type : types) {
            Annotation declared = owner.getAnnotation(type);
            if (declared != null) {
                keys.add(listed(keyOf(owner, type), type, declared));
            }
        }
        for (Field field : fieldsOf(owner)) {
            for (Class<? extends Annotation> type : types) {
                Annotation declared = field.getAnnotation(type);
                if (declared != null) {
                    keys.add(listed(keyOf(field.getDeclaringClass(), field.getName(), type), type, declared));
                }
            }
        }
        return keys;
    }

    private static <A extends Annotation> ResolvedAnnotation<A> resolved(Class<?> builtFor, List<String> keys, Class<A> type, A declared) {
        // What the code does not carry is not there: the store is not asked and cannot add it
        if (declared == null) {
            return null;
        }
        AnnotationResolver current = resolver;
        if (current == null) {
            return new ResolvedAnnotation<>(keys.getFirst(), type, declared, null);
        }
        long generation = GENERATION.get();
        Held held = HELD.compute(builtFor, (owner, existing) -> existing != null && existing.generation == generation ? existing : new Held(generation));
        synchronized (held) {
            if (!held.asked.containsAll(keys)) {
                // One question per class: every annotation the class and its fields carry goes
                // with the keys wanted now, so a schema of fifty fields is one read
                Set<String> wanted = candidateKeys(builtFor);
                wanted.addAll(keys);
                wanted.removeAll(held.asked);
                Map<String, AnnotationOverride> answer = current.resolve(Collections.unmodifiableSet(wanted));
                if (answer == null) {
                    throw new IllegalStateException("AnnotationResolver " + current.getClass().getName() + " answered null; a resolver that holds nothing answers an empty map");
                }
                for (String key : wanted) {
                    AnnotationOverride override = answer.get(key);
                    if (override != null) {
                        held.found.put(key, override);
                    }
                }
                held.asked.addAll(wanted);
            }
            for (String key : keys) {
                AnnotationOverride override = held.found.get(key);
                if (override != null) {
                    return new ResolvedAnnotation<>(key, type, declared, override);
                }
            }
        }
        return new ResolvedAnnotation<>(keys.getFirst(), type, declared, null);
    }

    /** The keys of every annotation the class and its fields carry, both keys of an inherited field. */
    private static Set<String> candidateKeys(Class<?> owner) {
        Set<String> keys = new LinkedHashSet<>();
        for (Class<? extends Annotation> type : KNOWN) {
            if (owner.isAnnotationPresent(type)) {
                keys.add(keyOf(owner, type));
            }
        }
        for (Field field : fieldsOf(owner)) {
            for (Class<? extends Annotation> type : KNOWN) {
                if (field.isAnnotationPresent(type)) {
                    keys.add(keyOf(owner, field.getName(), type));
                    keys.add(keyOf(field.getDeclaringClass(), field.getName(), type));
                }
            }
        }
        return keys;
    }

    /** The instance fields of a class and of its superclasses, the class's own first. */
    private static List<Field> fieldsOf(Class<?> owner) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> walked = owner; walked != null && walked != Object.class; walked = walked.getSuperclass()) {
            for (Field field : walked.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    private static AnnotationKey listed(String key, Class<? extends Annotation> type, Annotation declared) {
        String value = null;
        ObjectNode attributes = JsonNodeFactory.instance.objectNode();
        for (Method method : type.getDeclaredMethods()) {
            Object read;
            try {
                read = method.invoke(declared);
            }
            catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read " + method.getName() + " of @" + type.getSimpleName(), e);
            }
            if (ResolvedAnnotation.VALUE.equals(method.getName())) {
                if (read instanceof String text) {
                    value = text;
                }
                else if (read instanceof String[] entries) {
                    value = String.join("\n", entries);
                }
            }
            else if (read instanceof Boolean flag) {
                attributes.put(method.getName(), flag);
            }
            else if (read instanceof Integer number) {
                attributes.put(method.getName(), number);
            }
            else if (read instanceof String text) {
                attributes.put(method.getName(), text);
            }
            else if (read instanceof Enum<?> constant) {
                attributes.put(method.getName(), constant.name());
            }
            else if (read instanceof String[] entries) {
                ArrayNode array = attributes.putArray(method.getName());
                for (String entry : entries) {
                    array.add(entry);
                }
            }
        }
        return new AnnotationKey(key, value, attributes.isEmpty() ? null : attributes);
    }
}
