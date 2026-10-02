/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import java.lang.annotation.*;

/**
 * Annotation to provide a description of what a tool does.
 * This description is used by the LLM to understand when and how to use the tool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-19)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolDescription {
    String value();

    /**
     * Declares that this tool observes the environment without changing it: no database
     * writes, no file writes, no messages sent, nothing left different from how it was
     * found.
     *
     * <p>The test is whether anything observable afterwards has changed, not whether a
     * byte was written somewhere. Bookkeeping a service creates in order to answer a read
     * does not disqualify a tool - a search engine caching the page it fetched, or a
     * document-parsing service holding an uploaded copy so it can return the text, are
     * the mechanism of reading rather than a change to the environment. Deriving a new
     * artifact from an input is likewise a read: the input is untouched.
     *
     * <p>Orchestrators (doers, thinkers) may carry the flag only when everything they can
     * reach is itself read-only, transitively and through a closed tool palette.
     *
     * <p>Defaults to false - a tool is assumed to mutate unless explicitly declared
     * otherwise, so an unmarked tool and an unreviewed tool never have to be told apart.
     */
    boolean readOnly() default false;
}