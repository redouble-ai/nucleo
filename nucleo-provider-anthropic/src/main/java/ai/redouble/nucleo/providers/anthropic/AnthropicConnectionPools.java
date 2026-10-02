/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import org.slf4j.*;
import java.lang.reflect.*;
import java.util.concurrent.*;

/**
 * Tunes the OkHttp connection pool buried inside an Anthropic SDK client. The Anthropic SDK
 * does not expose its pool, so this reaches in reflectively and rebuilds the client with a
 * pool sized to {@link AnthropicSettings#connectionPoolSize}. Best-effort: if the SDK
 * internals change, it logs and leaves the SDK default in place.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
final class AnthropicConnectionPools {
    private static final Logger log = LoggerFactory.getLogger(AnthropicConnectionPools.class);
    private AnthropicConnectionPools() {}

    static void configure(AnthropicSDKClient anthropicClient, int maxIdleConnections) {
        try {
            Object sdkClient = anthropicClient.client;
            if (sdkClient == null) {
                return;
            }
            Object okHttpClient = findFieldByTypeName(sdkClient, "okhttp3.OkHttpClient", 3);
            if (okHttpClient == null) {
                log.debug("Could not find OkHttpClient in {} - using SDK default connection pool", sdkClient.getClass().getName());
                return;
            }
            Class<?> poolClass = Class.forName("okhttp3.ConnectionPool");
            Object newPool = poolClass.getConstructor(int.class, long.class, TimeUnit.class)
                    .newInstance(maxIdleConnections, 5L, TimeUnit.MINUTES);
            Object builder = okHttpClient.getClass().getMethod("newBuilder").invoke(okHttpClient);
            builder.getClass().getMethod("connectionPool", poolClass).invoke(builder, newPool);
            Object newOkHttpClient = builder.getClass().getMethod("build").invoke(builder);
            replaceFieldByValue(sdkClient, okHttpClient, newOkHttpClient, 3);
            log.info("Configured Anthropic OkHttp connection pool: {} max idle connections", maxIdleConnections);
        }
        catch (Throwable t) {
            log.warn("Could not configure Anthropic connection pool (SDK internals may have changed): {}", t.getMessage());
        }
    }

    /**
     * Searches an object's field hierarchy (up to maxDepth) for a field whose runtime type
     * matches the given class name. Returns the field value, or null if not found.
     * Empty catch blocks are intentional - reflective access may fail for inaccessible
     * fields (module boundaries, synthetic fields), which are expected and skipped.
     */
    private static Object findFieldByTypeName(Object root, String typeName, int maxDepth) {
        if (maxDepth <= 0 || root == null) {
            return null;
        }
        Class<?> clazz = root.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(root);
                    if (value != null && value.getClass().getName().equals(typeName)) {
                        return value;
                    }
                }
                catch (Exception ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        clazz = root.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                try {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(root);
                    if (value != null && !value.getClass().getName().startsWith("java.") &&
                        !value.getClass().isPrimitive()) {
                        Object found = findFieldByTypeName(value, typeName, maxDepth - 1);
                        if (found != null) {
                            return found;
                        }
                    }
                }
                catch (Exception ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * Replaces the field currently holding oldValue with newValue, searching up to maxDepth.
     */
    private static boolean replaceFieldByValue(Object root, Object oldValue, Object newValue, int maxDepth) {
        if (maxDepth <= 0 || root == null) {
            return false;
        }
        Class<?> clazz = root.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(root);
                    if (value == oldValue) {
                        field.set(root, newValue);
                        return true;
                    }
                }
                catch (Exception ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        clazz = root.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                try {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(root);
                    if (value != null && !value.getClass().getName().startsWith("java.") &&
                        !value.getClass().isPrimitive()) {
                        if (replaceFieldByValue(value, oldValue, newValue, maxDepth - 1)) {
                            return true;
                        }
                    }
                }
                catch (Exception ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }
}
