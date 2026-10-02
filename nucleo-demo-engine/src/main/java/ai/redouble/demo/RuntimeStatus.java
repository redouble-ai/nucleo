/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import java.util.*;

/**
 * The whole status panel in one shape: whether the dispatcher runs, the providers, the catalog
 * summary, the catalog entries, per grade the entry the runtime serves each kind of request with
 * now (text, images, documents) and, where it serves none, its refusal in its own words, the
 * shipped corpus path the page prefills, and the day the corpus's price story is answered for,
 * which the pricing steps open on.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record RuntimeStatus(boolean dispatcherRunning, List<ProviderStatus> providers, DemoCatalog.Status catalog,
                            List<CatalogEntry> entries, Map<String, Map<String, String>> serving,
                            Map<String, Map<String, String>> servingRefusals, String corpus, String corpusAsOf) {}
