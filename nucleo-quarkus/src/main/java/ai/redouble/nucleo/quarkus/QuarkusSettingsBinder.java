/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.quarkus;

import ai.redouble.nucleo.*;
import org.eclipse.microprofile.config.*;
import org.slf4j.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * Binds {@code nucleo.*} application properties onto the registered {@link Settings} classes,
 * one uniform rule for every module's knobs: the property for {@code HttpSettings.poolSize} is
 * {@code nucleo.http.pool-size} - the class's simple name minus {@code Settings}, then the
 * field, both kebab-cased. A bound property overwrites whatever the deployment's
 * {@link NucleoConfigurator} set, so properties win over code.
 *
 * <p>Field types follow the settings contract: primitives and their boxes (a box left null
 * means the knob is unset), {@code String}, and {@code Class} (bound from the fully qualified
 * class name). This is the MicroProfile Config half of what
 * {@code SpringSettingsBinder} does for a Boot host; the rule and the property spelling are
 * the same, so a knob reads the same in either host.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
final class QuarkusSettingsBinder {
    private static final Logger log = LoggerFactory.getLogger(QuarkusSettingsBinder.class);

    private QuarkusSettingsBinder() {
    }

    static void bind() {
        Config config = ConfigProvider.getConfig();
        ServiceLoader.load(Settings.class).stream().forEach(provider -> bindType(config, provider.type()));
    }

    private static void bindType(Config config, Class<? extends Settings> type) {
        String prefix = "nucleo." + kebab(type.getSimpleName().replaceAll("Settings$", ""));
        Settings settings = Settings.get(type);
        for (Field field : type.getFields()) {
            String property = prefix + "." + kebab(field.getName());
            Optional<String> raw = config.getOptionalValue(property, String.class);
            if (raw.isEmpty()) {
                continue;
            }
            try {
                field.set(settings, valueFor(config, property, raw.get(), field.getType()));
                log.info("Bound {} -> {}.{}", property, type.getSimpleName(), field.getName());
            }
            catch (IllegalAccessException | ClassNotFoundException e) {
                throw new IllegalStateException("Cannot bind property " + property + " to " + type.getSimpleName() + "." + field.getName(), e);
            }
        }
    }

    private static Object valueFor(Config config, String property, String raw, Class<?> fieldType) throws ClassNotFoundException {
        if (fieldType == Class.class) {
            return Class.forName(raw, true, Thread.currentThread().getContextClassLoader());
        }
        return config.getValue(property, fieldType);
    }

    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}
