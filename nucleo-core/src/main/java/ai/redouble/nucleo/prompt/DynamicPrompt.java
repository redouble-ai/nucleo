/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

import java.lang.annotation.*;

/**
 * Declares a registered Prompt source whose content may vary per call. The framework
 * re-produces it on every {@link Prompts#produce(String)} and re-runs guardrails. Use
 * sparingly.
 *
 * <p>Prefer {@link StaticPrompt} unless the content genuinely depends on runtime state
 * (today's date, DB-backed live content, A/B dispatch, feature-flag routing). Only
 * process-global reads (clock, env, feature registry) are allowed inside a source body;
 * per-caller or per-request state belongs on {@code ThinkerObjective.input}, not in the
 * prompt.
 *
 * <p>Valid placements:
 * <ul>
 *   <li>{@code TYPE}: a class implementing {@link PromptSource} but NOT
 *       {@link StaticPromptSource}, with a no-arg constructor.</li>
 *   <li>{@code FIELD}: {@code static final PromptSource} (not a StaticPromptSource).</li>
 *   <li>{@code METHOD}: {@code static} no-arg method returning {@link PromptSource}
 *       (not a StaticPromptSource).</li>
 *   <li>Instance method override on a {@code SingleObjectiveThinker} subclass that returns
 *       a {@link Prompt} directly: the base class machinery re-invokes every produce.</li>
 * </ul>
 *
 * <p>{@code String}-typed fields are rejected - a {@code String} is structurally immutable
 * and belongs under {@link StaticPrompt}.
 *
 * <p>Key auto-derivation rules match {@link StaticPrompt}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
public @interface DynamicPrompt {
    String value() default "";
}
