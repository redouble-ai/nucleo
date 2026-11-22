/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.annotations;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.prompt.*;
import com.fasterxml.jackson.databind.*;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * An annotation the code carries, as it reads once the deployment's store has been
 * consulted: each attribute is the stored one when the store holds it and the code's
 * otherwise.
 *
 * <p>Attributes are read by name, since what is stored cannot be an instance of the
 * annotation. The stored override was checked against the annotation when this was made, so
 * a read never meets a stored value of the wrong type.
 *
 * @param <A> the annotation
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-22)
 */
public final class ResolvedAnnotation<A extends Annotation> {
    static final String VALUE = "value";

    private final String key;
    private final Class<A> type;
    private final A declared;
    private final AnnotationOverride override;

    ResolvedAnnotation(String key, Class<A> type, A declared, AnnotationOverride override) {
        this.key = key;
        this.type = type;
        this.declared = declared;
        this.override = override;
        if (override != null) {
            check();
        }
    }

    /** The key the store was asked for and, when it answered, answered under. */
    public String key() {
        return key;
    }

    /** Whether the store holds anything for this annotation. */
    public boolean overridden() {
        return override != null;
    }

    /** The annotation as the code declares it. */
    public A declared() {
        return declared;
    }

    /** A text attribute. For {@code value} the stored text wins. */
    public String string(String attribute) {
        if (VALUE.equals(attribute)) {
            if (override != null && override.getValue() != null) {
                return override.getValue();
            }
            return (String)fromCode(attribute);
        }
        JsonNode stored = stored(attribute);
        return stored != null ? stored.asText() : (String)fromCode(attribute);
    }

    /**
     * The {@code value} as text a model is about to read. Stored text passes the baseline
     * prompt guardrails first, as a substituted prompt does; the code's own text is the
     * code's and is not checked here.
     *
     * @throws AnnotationOverrideException when a guardrail refuses the stored text
     */
    public String text() {
        if (override != null && override.getValue() != null) {
            guard(override.getValue());
        }
        return string(VALUE);
    }

    /** As {@link #text()}, for a {@code value} that is a list of strings. */
    public String[] texts() {
        if (override != null && override.getValue() != null) {
            guard(override.getValue());
        }
        return strings(VALUE);
    }

    private void guard(String stored) {
        try {
            Prompts.of(stored);
        }
        catch (GuardrailException e) {
            throw new AnnotationOverrideException(key, "a prompt guardrail refused its text", e);
        }
    }

    /** A list-of-strings attribute. A stored {@code value} is one entry per line. */
    public String[] strings(String attribute) {
        if (VALUE.equals(attribute)) {
            if (override != null && override.getValue() != null) {
                return lines(override.getValue());
            }
            return (String[])fromCode(attribute);
        }
        JsonNode stored = stored(attribute);
        if (stored == null) {
            return (String[])fromCode(attribute);
        }
        String[] entries = new String[stored.size()];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = stored.get(i).asText();
        }
        return entries;
    }

    public boolean bool(String attribute) {
        JsonNode stored = stored(attribute);
        return stored != null ? stored.booleanValue() : (Boolean)fromCode(attribute);
    }

    public int integer(String attribute) {
        JsonNode stored = stored(attribute);
        return stored != null ? stored.intValue() : (Integer)fromCode(attribute);
    }

    public <E extends Enum<E>> E enumValue(String attribute, Class<E> enumType) {
        JsonNode stored = stored(attribute);
        return stored != null ? Enum.valueOf(enumType, stored.asText()) : enumType.cast(fromCode(attribute));
    }

    static String[] lines(String text) {
        List<String> entries = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                entries.add(line.strip());
            }
        }
        return entries.toArray(new String[0]);
    }

    private JsonNode stored(String attribute) {
        if (override == null || override.getAttributes() == null) {
            return null;
        }
        return override.getAttributes().get(attribute);
    }

    /** The attribute as the code's annotation states it. */
    private Object fromCode(String attribute) {
        try {
            return type.getMethod(attribute).invoke(declared);
        }
        catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("@" + type.getSimpleName() + " has no attribute " + attribute, e);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read " + attribute + " of @" + type.getSimpleName(), e);
        }
    }

    /** Refuses a stored override the annotation cannot take. */
    private void check() {
        if (override.getValue() != null) {
            Class<?> valueType = attributeType(VALUE);
            if (valueType != String.class && valueType != String[].class) {
                throw new AnnotationOverrideException(key, "@" + type.getSimpleName() + " has no text value; its attributes are " + attributeNames());
            }
        }
        JsonNode attributes = override.getAttributes();
        if (attributes == null) {
            return;
        }
        if (!attributes.isObject()) {
            throw new AnnotationOverrideException(key, "the attributes are a JSON object keyed by attribute name");
        }
        for (Iterator<Map.Entry<String, JsonNode>> entries = attributes.fields(); entries.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String name = entry.getKey();
            if (VALUE.equals(name)) {
                throw new AnnotationOverrideException(key, "value is stored apart from the attributes, never among them");
            }
            Class<?> expected = attributeType(name);
            if (expected == null) {
                throw new AnnotationOverrideException(key, "@" + type.getSimpleName() + " has no attribute " + name + "; its attributes are " + attributeNames());
            }
            if (!fits(entry.getValue(), expected)) {
                throw new AnnotationOverrideException(key, "the attribute " + name + " takes " + describe(expected));
            }
        }
    }

    private Class<?> attributeType(String attribute) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(attribute)) {
                return method.getReturnType();
            }
        }
        return null;
    }

    private List<String> attributeNames() {
        List<String> names = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            names.add(method.getName());
        }
        Collections.sort(names);
        return names;
    }

    private static boolean fits(JsonNode stored, Class<?> expected) {
        if (expected == boolean.class) {
            return stored.isBoolean();
        }
        if (expected == int.class) {
            return stored.isInt();
        }
        if (expected == String.class) {
            return stored.isTextual();
        }
        if (expected == String[].class) {
            if (!stored.isArray()) {
                return false;
            }
            for (JsonNode element : stored) {
                if (!element.isTextual()) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isEnum()) {
            if (!stored.isTextual()) {
                return false;
            }
            for (Object constant : expected.getEnumConstants()) {
                if (((Enum<?>)constant).name().equals(stored.asText())) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private static String describe(Class<?> expected) {
        if (expected == boolean.class) {
            return "true or false";
        }
        if (expected == int.class) {
            return "a whole number";
        }
        if (expected == String.class) {
            return "text";
        }
        if (expected == String[].class) {
            return "a list of texts";
        }
        if (expected.isEnum()) {
            return "one of " + Arrays.toString(expected.getEnumConstants());
        }
        return "a value that cannot be stored; this attribute stays in the code";
    }
}
