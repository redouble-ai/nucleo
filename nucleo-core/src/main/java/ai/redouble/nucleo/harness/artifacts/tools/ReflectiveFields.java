/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts.tools;

import java.lang.reflect.*;

/**
 * Field lookup shared by tools that address artifact fields by LLM-supplied
 * name: accepts snake_case or camelCase and walks the class hierarchy.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-12)
 */
public final class ReflectiveFields {
    private ReflectiveFields() {
    }

    /**
     * Finds the named field on the class or any superclass, accepting either
     * snake_case or camelCase. The returned field is accessible.
     *
     * @return the field, or null if no such field exists
     */
    public static Field find(Class<?> clazz, String fieldName) {
        String camelCase = toCamelCase(fieldName);
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.getName().equals(camelCase) || field.getName().equals(fieldName)) {
                    field.setAccessible(true);
                    return field;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * Declared field names of the class and its ancestors, skipping static,
     * transient and synthetic fields - the addressable surface {@link #find}
     * resolves against.
     */
    public static java.util.List<String> fieldNames(Class<?> type) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers())
                        && !field.isSynthetic()) {
                    names.add(field.getName());
                }
            }
        }
        return names;
    }

    /**
     * Separator- and case-insensitive form of a field name for matching
     * dynamic record fields: lowercased with every non-alphanumeric character
     * removed, so {@code sender_type}, {@code "Sender Type"} and
     * {@code senderType} all collapse to {@code sendertype}. Used to match an
     * LLM-supplied predicate field against a {@link KeyedRecord}'s field names,
     * which carry whatever labels an extraction plan chose.
     */
    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /**
     * Converts snake_case to camelCase. camelCase input passes through unchanged.
     */
    public static String toCamelCase(String name) {
        if (name.indexOf('_') < 0) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name.length());
        boolean upperNext = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_') {
                upperNext = true;
            }
            else if (upperNext) {
                sb.append(Character.toUpperCase(c));
                upperNext = false;
            }
            else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
