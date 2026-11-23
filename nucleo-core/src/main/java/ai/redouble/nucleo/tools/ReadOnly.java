/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import java.lang.annotation.*;

/**
 * Declares that a tool observes the environment without changing it: no database writes, no
 * file writes, no messages sent, nothing left different from how it was found. A read-only
 * thinker may call only tools that carry it.
 *
 * <p>The test is whether anything observable afterwards has changed, not whether a byte was
 * written somewhere. Bookkeeping a service creates in order to answer a read does not
 * disqualify a tool: a search engine caching the page it fetched, or a document-parsing
 * service holding an uploaded copy so it can return the text, are the mechanism of reading
 * rather than a change to the environment. Deriving a new artifact from an input is likewise
 * a read: the input is untouched.
 *
 * <p>An orchestrator (a doer, a thinker) may carry it only when everything it can reach is
 * itself read-only, transitively and through a closed tool palette.
 *
 * <p>A tool without it is assumed to change something, so an unmarked tool and an unreviewed
 * tool never have to be told apart. The marker has no attributes on purpose: what a read-only
 * thinker may call is a fact about the code, and nothing stored outside the code can state
 * it.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-22)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface ReadOnly {
}
