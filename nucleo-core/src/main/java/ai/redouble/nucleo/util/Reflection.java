/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.util;

import java.lang.reflect.*;

/**
 * Construction of configured classes by name or type: the deployment's configurator, the models backend,
 * the model picker, the secrets store, the discovered client providers. A class that cannot be
 * found or built throws {@link IllegalStateException} with the cause attached; there is no
 * fallback to guess at.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-10)
 */
public final class Reflection {

    private Reflection() {
    }

    /** The class by binary name, through the class loader that loaded the runtime. */
    public static Class<?> forName(String className) {
        try {
            return Class.forName(className);
        }
        catch (ClassNotFoundException e) {
            throw new IllegalStateException("Class not found: " + className, e);
        }
    }

    /** A new instance of the named class through its no-arg constructor. */
    public static Object newInstance(String className) {
        return newInstance(forName(className));
    }

    /** A new instance through the no-arg constructor, made accessible when it is not public. */
    public static <T> T newInstance(Class<? extends T> type) {
        try {
            Constructor<? extends T> constructor = type.getDeclaredConstructor();
            if (!constructor.canAccess(null)) {
                constructor.setAccessible(true);
            }
            return constructor.newInstance();
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate " + type.getName(), e);
        }
    }
}
