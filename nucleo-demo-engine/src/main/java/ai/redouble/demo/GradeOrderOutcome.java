/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import java.util.*;

/**
 * What ordering the grades at a first discovery did: the model that judged, how many models
 * ran and how many of them are in no order (a failed run, or no score), what the race cost,
 * each grade's order as it was written, the score behind each placed entry, and, when no order
 * could be written, why. A failure leaves the catalog as the discovery wrote it, with no
 * order.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-23)
 */
public record GradeOrderOutcome(String judge, int measured, int leftOut, Double cost, String currency,
                                Map<String, List<String>> orders, Map<String, Double> scores, String failure) {}
