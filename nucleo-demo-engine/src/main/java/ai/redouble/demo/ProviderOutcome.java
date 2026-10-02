/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

/**
 * One queried provider's outcome, readable at a glance: LISTED with a count, LISTING_FAILED with
 * the reason, LISTING_UNSUPPORTED (pinged blind). {@code unknown} is how many of the listed
 * models got NO entry: the catalog held no ancestor to inherit a shape from, and the discovery
 * never invents grades, prices or limits - a person writes those entries.
 */
public record ProviderOutcome(String key, String status, Integer listed, Integer unknown, String detail) {}
