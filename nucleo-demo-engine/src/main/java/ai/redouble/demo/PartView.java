/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

/**
 * One part of a credential: which part (user, secret, host), the environment variable it reads from, and what it means.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record PartView(String part, String variable, String meaning) {}
