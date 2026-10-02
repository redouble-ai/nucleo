/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.guardrails;

/**
 * Marker interface for tool inputs that contain a URL to fetch.
 * Inputs implementing this are eligible for {@link UrlGuardrail} validation.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-13)
 */
public interface UrlInput {
    String getUrl();
}
