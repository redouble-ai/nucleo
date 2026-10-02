/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import java.util.*;

/**
 * A run of the extractor: which directory, at most how many files, and the spend it may
 * commit. {@code budgets} is one amount per currency the deployment pays in; a run with
 * none is uncapped, which is the caller's decision and not the default of anything.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class ExtractRequest {
    private String directory;
    private Integer maxFiles;
    private List<Budget> budgets;

    /** One cap: an amount in an ISO 4217 currency. */
    public static class Budget {
        private double amount;
        private String currency;

        public double getAmount() {return amount;}

        public void setAmount(double amount) {this.amount = amount;}

        public String getCurrency() {return currency;}

        public void setCurrency(String currency) {this.currency = currency;}
    }

    public String getDirectory() {return directory;}

    public void setDirectory(String directory) {this.directory = directory;}

    public Integer getMaxFiles() {return maxFiles;}

    public void setMaxFiles(Integer maxFiles) {this.maxFiles = maxFiles;}

    public List<Budget> getBudgets() {return budgets;}

    public void setBudgets(List<Budget> budgets) {this.budgets = budgets;}
}
