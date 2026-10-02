/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

import java.lang.annotation.*;

/**
 * Declares a registered Prompt source whose content is promised immutable. The framework
 * produces it once and caches the resulting Prompt indefinitely; cached hits re-validate
 * only when the guardrail chain changed since the last validation (the result is memoized
 * by content hash and chain fingerprint), so the steady state costs one map read.
 *
 * <p>Default choice. Prefer this over {@link DynamicPrompt} whenever a prompt's text does
 * not genuinely vary at runtime. Per-call production cost is real; take the static promise
 * whenever you can keep it.
 *
 * <p>Valid placements:
 * <ul>
 *   <li>{@code TYPE}: a class implementing {@link StaticPromptSource} with a no-arg
 *       constructor. Scanner instantiates once and registers.</li>
 *   <li>{@code FIELD}: {@code static final String} (wrapped in
 *       {@link ai.redouble.nucleo.prompt.sources.StaticTextSource}) or
 *       {@code static final StaticPromptSource}.</li>
 *   <li>{@code METHOD}: {@code static} no-arg method returning {@link StaticPromptSource},
 *       invoked once at scan time.</li>
 *   <li>Instance method override on a {@code SingleObjectiveThinker} subclass: the base
 *       class's runtime machinery auto-derives the key from the subclass FQN and registers
 *       the returned text under a StaticTextSource on first construction.</li>
 * </ul>
 *
 * <p>If {@link #value} is empty, the key is auto-derived: {@code <declaring-class-fqn>}
 * for type / thinker-override placements, {@code <declaring-class-fqn>.<member-name>}
 * for static fields and static methods.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
public @interface StaticPrompt {
    String value() default "";
}
