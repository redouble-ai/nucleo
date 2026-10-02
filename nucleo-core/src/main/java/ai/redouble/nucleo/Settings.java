/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo;

import ai.redouble.nucleo.util.*;
import org.slf4j.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The base of every runtime tunable. A package that owns knobs declares exactly one subclass,
 * named {@code <Area>Settings}, next to the code that reads the knobs: plain mutable public
 * fields with the shipped default inline, no builders, no fluent setters. Readers obtain the
 * one instance per type through {@link #get}, so "where do I tune X" has the same answer in
 * every module: the settings class of the package that owns X.
 *
 * <p>The first {@link #get} anywhere runs the deployment's {@link NucleoConfigurator} once,
 * before returning: the class named by {@code -Dnucleo.env}, else the single registration
 * under {@code META-INF/services/ai.redouble.nucleo.NucleoConfigurator}, else no configurator
 * and the shipped defaults stand. Two registrations refuse with both names - the classpath
 * order that would otherwise pick one silently is never a configuration. A configurator that
 * throws poisons the runtime: every later {@link #get} rethrows that first failure rather
 * than running on half-applied configuration.
 *
 * <p>Every subclass ships a line under {@code META-INF/services/ai.redouble.nucleo.Settings};
 * that registration is what lets {@link #printAll} enumerate every knob in the deployment -
 * including ones contributed by other jars - with its current value.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
public abstract class Settings {
    private static final Logger log = LoggerFactory.getLogger(Settings.class);
    private static final Map<Class<? extends Settings>, Settings> instances = new ConcurrentHashMap<>();
    private static final Object CONFIGURE_LOCK = new Object();
    private static volatile boolean configured;
    private static volatile RuntimeException configurationFailure;
    private static volatile Thread configuringThread;

    /** The one instance of the given settings type, after the deployment's configurator has run. */
    public static <S extends Settings> S get(Class<S> type) {
        ensureConfigured();
        return type.cast(instances.computeIfAbsent(type, Reflection::newInstance));
    }

    /**
     * Every registered settings class with every public field's current value, one line each -
     * the whole-deployment view.
     */
    static String printAll() {
        SortedMap<String, Class<? extends Settings>> types = new TreeMap<>();
        ServiceLoader.load(Settings.class).stream().forEach(provider -> types.put(provider.type().getName(), provider.type()));
        for (Class<? extends Settings> instantiated : instances.keySet()) {
            types.put(instantiated.getName(), instantiated);
        }
        StringBuilder s = new StringBuilder("\n================ SETTINGS ===============\n");
        for (Class<? extends Settings> type : types.values()) {
            Settings settings = get(type);
            for (Field field : type.getFields()) {
                if (field.getDeclaringClass() == Settings.class) {
                    continue;
                }
                s.append(type.getSimpleName()).append('.').append(field.getName()).append(" = ");
                try {
                    Object value = field.get(settings);
                    s.append(value instanceof Class<?> c ? c.getName() : value);
                }
                catch (IllegalAccessException e) {
                    s.append(e.getMessage());
                }
                s.append('\n');
            }
        }
        s.append("================ -------- ===============\n");
        return s.toString();
    }

    private static void ensureConfigured() {
        if (configured || Thread.currentThread() == configuringThread) {
            if (configurationFailure != null) {
                throw new IllegalStateException("The deployment configurator failed at startup", configurationFailure);
            }
            return;
        }
        synchronized (CONFIGURE_LOCK) {
            if (configured) {
                if (configurationFailure != null) {
                    throw new IllegalStateException("The deployment configurator failed at startup", configurationFailure);
                }
                return;
            }
            configuringThread = Thread.currentThread();
            try {
                NucleoConfigurator configurator = discover();
                if (configurator != null) {
                    configurator.configure();
                }
            }
            catch (RuntimeException e) {
                configurationFailure = e;
                throw new IllegalStateException("The deployment configurator failed at startup", e);
            }
            finally {
                configuringThread = null;
                configured = true;
            }
        }
    }

    /**
     * The deployment's configurator: the class named by {@link NucleoConfigurator#CONFIG_PROPERTY},
     * else the one services registration, else null (shipped defaults).
     */
    private static NucleoConfigurator discover() {
        String named = System.getProperty(NucleoConfigurator.CONFIG_PROPERTY);
        List<NucleoConfigurator> registered = new ArrayList<>();
        for (NucleoConfigurator candidate : ServiceLoader.load(NucleoConfigurator.class)) {
            registered.add(candidate);
        }
        NucleoConfigurator chosen = choose(named, registered);
        if (chosen == null) {
            log.info("No NucleoConfigurator registered under META-INF/services and no -D{}; running on the shipped defaults",
                    NucleoConfigurator.CONFIG_PROPERTY);
        }
        return chosen;
    }

    /** The choice rule as a pure function of its inputs, so the refusal unit-tests without classpath games. Package-private for its test. */
    static NucleoConfigurator choose(String namedClass, List<NucleoConfigurator> registered) {
        if (namedClass != null) {
            log.info("NucleoConfigurator {} named by -D{}", namedClass, NucleoConfigurator.CONFIG_PROPERTY);
            return (NucleoConfigurator)Reflection.newInstance(namedClass);
        }
        if (registered.size() > 1) {
            throw new IllegalStateException("More than one NucleoConfigurator registered under META-INF/services: "
                    + registered.stream().map(c -> c.getClass().getName()).toList()
                    + ". Keep one on the classpath, or name the one to use with -D" + NucleoConfigurator.CONFIG_PROPERTY);
        }
        if (registered.isEmpty()) {
            return null;
        }
        log.info("NucleoConfigurator {} registered under META-INF/services", registered.getFirst().getClass().getName());
        return registered.getFirst();
    }
}
