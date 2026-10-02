/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

/**
 * An edit to one catalog entry from the page: the entry's id, and any of a new grade, a new
 * status, a new input or output price per million in the entry's currency, or a confirmation
 * that the entry's inferred facts were checked; null leaves a field as it is.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record EntryEdit(String id, String grade, String status, Double inputPricePerMillion, Double outputPricePerMillion, Boolean confirmed) {}
