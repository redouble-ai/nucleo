/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.pricing;

import ai.redouble.nucleo.harness.schema.*;
import java.util.*;

/**
 * What the canonicalizer sees: every distinct product name the documents used, as they
 * used it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public class ProductNames {
    @LLMDescription("Product and service names as the documents wrote them, one per entry, duplicates removed")
    private List<String> names;

    public List<String> getNames() {return names;}

    public void setNames(List<String> names) {this.names = names;}
}
