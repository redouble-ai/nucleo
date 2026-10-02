/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

/**
 * Thrown at scan time - {@link Prompts#scanPackage(String)} or the auto-scan the first
 * {@code produce} triggers - for programmer errors in
 * {@link StaticPrompt} / {@link DynamicPrompt} declarations: duplicate keys, invalid
 * placement (non-static fields/methods that the scanner shouldn't touch are skipped, but
 * a static field/method that violates the contract throws), missing no-arg constructor on
 * annotated classes, or content type mismatched to the annotation's static/dynamic promise.
 *
 * <p>Runtime exception because it indicates a bug that must be fixed at compile-time of
 * the declaration, not handled at call sites.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-20)
 */
public class PromptRegistrationException extends RuntimeException {
    public PromptRegistrationException(String message) {
        super(message);
    }

    public PromptRegistrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
