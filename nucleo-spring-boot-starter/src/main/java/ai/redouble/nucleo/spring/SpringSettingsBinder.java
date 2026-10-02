/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.spring;

import ai.redouble.nucleo.*;
import org.slf4j.*;
import org.springframework.core.env.*;
import org.springframework.util.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * Binds {@code nucleo.*} application properties onto the registered {@link Settings}
 * classes, one uniform rule for every module's knobs: the property for
 * {@code HttpSettings.poolSize} is {@code nucleo.http.pool-size} - the class's simple name
 * minus {@code Settings}, then the field, both kebab-cased. A bound property overwrites
 * whatever the deployment's {@link NucleoConfigurator} set, so properties win over code.
 *
 * <p>Field types follow the settings contract: primitives and their boxes (a box left null
 * means the knob is unset), {@code String}, and {@code Class} (bound from the fully
 * qualified class name).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
final class SpringSettingsBinder {
    private static final Logger log = LoggerFactory.getLogger(SpringSettingsBinder.class);

    private SpringSettingsBinder() {
    }

    static void bind(Environment environment) {
        ServiceLoader.load(Settings.class).stream().forEach(provider -> bindType(environment, provider.type()));
    }

    private static void bindType(Environment environment, Class<? extends Settings> type) {
        String prefix = "nucleo." + kebab(type.getSimpleName().replaceAll("Settings$", ""));
        Settings settings = Settings.get(type);
        for (Field field : type.getFields()) {
            String property = prefix + "." + kebab(field.getName());
            if (!environment.containsProperty(property)) {
                continue;
            }
            try {
                field.set(settings, valueFor(environment, property, field.getType()));
                log.info("Bound {} -> {}.{}", property, type.getSimpleName(), field.getName());
            }
            catch (IllegalAccessException | ClassNotFoundException e) {
                throw new IllegalStateException("Cannot bind property " + property + " to " + type.getSimpleName() + "." + field.getName(), e);
            }
        }
    }

    private static Object valueFor(Environment environment, String property, Class<?> fieldType) throws ClassNotFoundException {
        if (fieldType == Class.class) {
            return ClassUtils.forName(environment.getProperty(property), SpringSettingsBinder.class.getClassLoader());
        }
        return environment.getProperty(property, fieldType);
    }

    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}
