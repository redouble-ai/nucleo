/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

/** A benchmark request: the query every raced model answers, and how many times each answers it. */
public record BenchmarkRequest(String query, Integer runs) {}
