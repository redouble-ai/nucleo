/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import java.util.*;

/**
 * What the discovery did: its report, the file written, the entry count, the providers this run
 * actually queried with each one's outcome, the entries the discovery's classifier wrote
 * ({@code classifiedBy} names the model whose judgment they are; each carries a verify note), the
 * classified entries whose own ping refused them, the new entries left out because the model
 * refuses the zero-retention mode the account runs at ({@code nonZdr}), the listed models no
 * provider key has a client for ({@code unserved}), and the status the page re-renders from.
 */
public record DiscoverOutcome(String report, String file, int entries, List<String> scope,
                              List<ProviderOutcome> providers, String classifiedBy, List<String> classified,
                              List<String> classifiedDropped, List<String> nonZdr, List<String> unserved,
                              String classifierFailure, RuntimeStatus status) {}
