/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import java.util.*;

/**
 * The spec classes a catalog entry is offered to: {@link StandardModelSpec} and every class a
 * {@code META-INF/services/ai.redouble.nucleo.harness.models.AbstractModelSpec} file on the
 * classpath names, found through the JDK {@link ServiceLoader} without one being instantiated.
 * A class is offered every entry, whichever provider serves it, and says itself whether the
 * entry is its own ({@link AbstractModelSpec#match()}).
 *
 * <p>The order is the one equal answers are settled by: a class nearer to
 * {@link AbstractModelSpec} comes first, so a superclass precedes its subclasses, and among
 * classes at the same distance the one whose name sorts first. It depends on nothing but the
 * classes, so the same classpath gives the same choice on every start.
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-14)
 */
final class SpecClasses {
    private SpecClasses() {
    }

    /** Every spec class on the classpath, in the order that settles equal answers. */
    static List<Class<? extends AbstractModelSpec>> find() {
        Set<Class<? extends AbstractModelSpec>> found = new HashSet<>();
        found.add(StandardModelSpec.class);
        ServiceLoader.load(AbstractModelSpec.class).stream().forEach(provider -> found.add(provider.type()));
        List<Class<? extends AbstractModelSpec>> ordered = new ArrayList<>(found);
        ordered.sort(Comparator.<Class<? extends AbstractModelSpec>>comparingInt(SpecClasses::distance).thenComparing(Class::getName));
        return ordered;
    }

    /** How many classes lie between a spec class and {@link AbstractModelSpec}, itself counted. */
    private static int distance(Class<?> specClass) {
        int distance = 0;
        for (Class<?> c = specClass; c != AbstractModelSpec.class; c = c.getSuperclass()) {
            distance++;
        }
        return distance;
    }
}
