/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package scannerrejects;

import ai.redouble.nucleo.prompt.*;

/**
 * Deliberately invalid fixture: {@code @StaticPrompt} on a type that does not implement
 * {@code PromptSource}. Lives entirely outside {@code ai.redouble} so no framework-wide
 * scan ever encounters it - only the rejection test's own scan of this package.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
@StaticPrompt("scanner.reject.no-source")
public class AnnotatedButNotASource {
}
