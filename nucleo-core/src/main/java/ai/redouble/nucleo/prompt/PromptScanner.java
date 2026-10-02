/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

import ai.redouble.nucleo.prompt.sources.*;
import org.reflections.*;
import org.reflections.scanners.*;
import org.reflections.util.*;

import org.slf4j.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * Classpath scanner for {@link StaticPrompt} and {@link DynamicPrompt} declarations.
 * Discovers annotated TYPE, FIELD, and METHOD elements via the Reflections library (same
 * mechanism as {@link ai.redouble.nucleo.harness.schema.TypeAliasRegistry}) and registers each as a default
 * {@link PromptSource} in the {@link Prompts} facade.
 *
 * <p>The annotation tells the scanner what contract the author is promising; the scanner
 * verifies the promise and rejects mismatches:
 * <ul>
 *   <li>{@link StaticPrompt} - content is immutable. Any source registered under this
 *       annotation must implement {@link StaticPromptSource} (String fields qualify because
 *       they are wrapped in {@link StaticTextSource}).</li>
 *   <li>{@link DynamicPrompt} - content may vary per call. The source must NOT implement
 *       {@link StaticPromptSource}, and {@code String}-typed fields are rejected since a
 *       String cannot vary.</li>
 * </ul>
 *
 * <p>Instance-level fields and instance methods carrying either annotation are ignored by
 * the scanner - they are handled at runtime by {@code SingleObjectiveThinker}'s template
 * machinery, which reads the annotation at construction time and routes to the correct
 * registration path.
 *
 * <p>If {@code value()} is empty the key is auto-derived: {@code <fqn>} for types,
 * {@code <fqn>.<member>} for static fields and static methods.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
final class PromptScanner {
    private static final Logger log = LoggerFactory.getLogger(PromptScanner.class);

    private PromptScanner() {
    }

    static void scan(String basePackage) {
        log.info("PromptScanner: scanning {}", basePackage);
        // ClasspathHelper.forPackage narrows URLS (whole classpath roots), not types: without
        // the input filter, scanning "a.b" validates and registers every annotated type in any
        // root that CONTAINS a.b - including unrelated packages.
        Reflections reflections = new Reflections(new ConfigurationBuilder().setUrls(ClasspathHelper.forPackage(basePackage))
                                                                            .filterInputsBy(new org.reflections.util.FilterBuilder().includePackage(basePackage))
                                                                            .setScanners(Scanners.TypesAnnotated, Scanners.FieldsAnnotated, Scanners.MethodsAnnotated));
        int typeCount = registerAnnotatedTypes(reflections);
        int fieldCount = registerAnnotatedFields(reflections);
        int methodCount = registerAnnotatedMethods(reflections);
        log.info("PromptScanner: registered {} types, {} fields, {} methods", typeCount, fieldCount, methodCount);
    }

    private static int registerAnnotatedTypes(Reflections reflections) {
        Set<Class<?>> all = new HashSet<>();
        all.addAll(reflections.getTypesAnnotatedWith(StaticPrompt.class));
        all.addAll(reflections.getTypesAnnotatedWith(DynamicPrompt.class));
        int count = 0;
        for (Class<?> cls : all) {
            if (registerType(cls)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Registers one {@link StaticPrompt}/{@link DynamicPrompt} type, or returns false for an
     * annotation or interface the scan skips. The one codepath both the classpath scan and the
     * Quarkus build-time recorder run through, so the annotation contract is validated in exactly
     * one place regardless of how the type was discovered.
     */
    static boolean registerType(Class<?> cls) {
        if (cls.isAnnotation() || cls.isInterface()) {
            return false;
        }
        StaticPrompt staticAnn = cls.getAnnotation(StaticPrompt.class);
        DynamicPrompt dynAnn = cls.getAnnotation(DynamicPrompt.class);
        rejectBothAnnotations(cls.getName(), staticAnn, dynAnn);
        boolean isStatic = staticAnn != null;
        String tag = tag(isStatic);
        if (!PromptSource.class.isAssignableFrom(cls)) {
            throw new PromptRegistrationException(tag + " on type " + cls.getName() + " requires implementing PromptSource");
        }
        boolean implementsStaticMarker = StaticPromptSource.class.isAssignableFrom(cls);
        if (isStatic && !implementsStaticMarker) {
            throw new PromptRegistrationException("@StaticPrompt type " + cls.getName() + " must implement StaticPromptSource");
        }
        if (!isStatic && implementsStaticMarker) {
            throw new PromptRegistrationException("@DynamicPrompt type " + cls.getName() + " must NOT implement StaticPromptSource");
        }
        Constructor<?> ctor;
        try {
            ctor = cls.getDeclaredConstructor();
        }
        catch (NoSuchMethodException e) {
            throw new PromptRegistrationException(tag + " on type " + cls.getName() + " requires a no-arg constructor", e);
        }
        ctor.setAccessible(true);
        String key = deriveKey(isStatic ? staticAnn.value() : dynAnn.value(), cls.getName());
        try {
            Prompts.registerDefault(key, (PromptSource)ctor.newInstance());
            return true;
        }
        catch (ReflectiveOperationException e) {
            throw new PromptRegistrationException("Failed to instantiate " + cls.getName() + " for key " + key, e);
        }
    }

    private static int registerAnnotatedFields(Reflections reflections) {
        Set<Field> all = new HashSet<>();
        all.addAll(reflections.getFieldsAnnotatedWith(StaticPrompt.class));
        all.addAll(reflections.getFieldsAnnotatedWith(DynamicPrompt.class));
        int count = 0;
        for (Field field : all) {
            if (registerField(field)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Registers one static {@link StaticPrompt}/{@link DynamicPrompt} field, or returns false for
     * an instance field the thinker runtime handles itself. Shared by the scan and the recorder.
     */
    static boolean registerField(Field field) {
        Candidate c = inspect(field);
        if (c == null) {
            return false;
        }
        if (!Modifier.isFinal(field.getModifiers())) {
            throw new PromptRegistrationException(c.tag() + " field " + c.memberId() + " must be final");
        }
        Class<?> type = field.getType();
        field.setAccessible(true);
        Object value;
        try {
            value = field.get(null);
        }
        catch (IllegalAccessException e) {
            throw new PromptRegistrationException("Failed to read field " + c.memberId(), e);
        }
        if (value == null) {
            throw new PromptRegistrationException(c.tag() + " field " + c.memberId() + " is null");
        }
        PromptSource source;
        if (type == String.class) {
            if (!c.isStatic()) {
                throw new PromptRegistrationException(
                        "@DynamicPrompt on String field " + c.memberId() + " is meaningless - use @StaticPrompt for String constants");
            }
            source = new StaticTextSource((String)value);
        }
        else if (PromptSource.class.isAssignableFrom(type)) {
            source = (PromptSource)value;
            boolean isStaticSrc = source instanceof StaticPromptSource;
            if (c.isStatic() && !isStaticSrc) {
                throw new PromptRegistrationException("@StaticPrompt field " + c.memberId() + " must hold a StaticPromptSource");
            }
            if (!c.isStatic() && isStaticSrc) {
                throw new PromptRegistrationException("@DynamicPrompt field " + c.memberId() + " must NOT hold a StaticPromptSource");
            }
        }
        else {
            throw new PromptRegistrationException(c.tag() + " field " + c.memberId() + " must be String or PromptSource, got " + type.getName());
        }
        Prompts.registerDefault(c.key(), source);
        return true;
    }

    /**
     * What a field and a method registration establish alike before they diverge on how the
     * value is read: which of the two annotations is present, the member's id for messages,
     * and the registry key.
     */
    private record Candidate(boolean isStatic, String tag, String memberId, String key) {
    }

    /**
     * The checks shared by both member kinds: exactly one annotation, and a static member.
     * Returns null for an instance member, which the thinker runtime registers itself.
     */
    private static <M extends AccessibleObject & Member> Candidate inspect(M member) {
        StaticPrompt staticAnn = member.getAnnotation(StaticPrompt.class);
        DynamicPrompt dynAnn = member.getAnnotation(DynamicPrompt.class);
        String memberId = member.getDeclaringClass().getName() + "#" + member.getName();
        rejectBothAnnotations(memberId, staticAnn, dynAnn);
        if (!Modifier.isStatic(member.getModifiers())) {
            return null;
        }
        boolean isStatic = staticAnn != null;
        String key = deriveKey(isStatic ? staticAnn.value() : dynAnn.value(), member.getDeclaringClass().getName() + "." + member.getName());
        return new Candidate(isStatic, tag(isStatic), memberId, key);
    }

    private static int registerAnnotatedMethods(Reflections reflections) {
        Set<Method> all = new HashSet<>();
        all.addAll(reflections.getMethodsAnnotatedWith(StaticPrompt.class));
        all.addAll(reflections.getMethodsAnnotatedWith(DynamicPrompt.class));
        int count = 0;
        for (Method method : all) {
            if (registerMethod(method)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Registers one static {@link StaticPrompt}/{@link DynamicPrompt} method, or returns false for
     * an instance method the thinker runtime handles itself. Shared by the scan and the recorder.
     */
    static boolean registerMethod(Method method) {
        Candidate c = inspect(method);
        if (c == null) {
            return false;
        }
        if (method.getParameterCount() != 0) {
            throw new PromptRegistrationException(c.tag() + " method " + c.memberId() + " must take no arguments");
        }
        if (!PromptSource.class.isAssignableFrom(method.getReturnType())) {
            throw new PromptRegistrationException(c.tag() + " method " + c.memberId() + " must return PromptSource, got " + method.getReturnType().getName());
        }
        method.setAccessible(true);
        PromptSource source;
        try {
            source = (PromptSource)method.invoke(null);
        }
        catch (IllegalAccessException | InvocationTargetException e) {
            throw new PromptRegistrationException("Failed to invoke method " + c.memberId(), e);
        }
        if (source == null) {
            throw new PromptRegistrationException(c.tag() + " method " + c.memberId() + " returned null");
        }
        boolean isStaticSrc = source instanceof StaticPromptSource;
        if (c.isStatic() && !isStaticSrc) {
            throw new PromptRegistrationException("@StaticPrompt method " + c.memberId() + " must return a StaticPromptSource");
        }
        if (!c.isStatic() && isStaticSrc) {
            throw new PromptRegistrationException("@DynamicPrompt method " + c.memberId() + " must NOT return a StaticPromptSource");
        }
        Prompts.registerDefault(c.key(), source);
        return true;
    }

    private static String deriveKey(String explicit, String autoFallback) {
        return explicit == null || explicit.isEmpty() ? autoFallback : explicit;
    }

    private static String tag(boolean isStatic) {
        return isStatic ? "@StaticPrompt" : "@DynamicPrompt";
    }

    private static void rejectBothAnnotations(String memberId, StaticPrompt s, DynamicPrompt d) {
        if (s != null && d != null) {
            throw new PromptRegistrationException(memberId + " has both @StaticPrompt and @DynamicPrompt - pick one");
        }
    }
}
