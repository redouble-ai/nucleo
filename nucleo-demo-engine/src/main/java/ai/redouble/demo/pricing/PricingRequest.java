/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.pricing;

import ai.redouble.demo.extract.*;
import java.util.*;

/**
 * A run of the pricing demo over the last extraction: the spend the run may commit, one cap
 * per currency, the same way the extractor is capped, and the day the run answers for. The
 * report is the response; no {@code asOf} means today, and a price dated after that day is
 * scheduled, not current.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public class PricingRequest {
    private String asOf;
    private List<ExtractRequest.Budget> budgets;

    public String getAsOf() {return asOf;}

    public void setAsOf(String asOf) {this.asOf = asOf;}

    public List<ExtractRequest.Budget> getBudgets() {return budgets;}

    public void setBudgets(List<ExtractRequest.Budget> budgets) {this.budgets = budgets;}
}
