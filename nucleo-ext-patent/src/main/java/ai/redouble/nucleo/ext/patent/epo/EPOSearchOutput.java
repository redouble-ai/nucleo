/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Output from EPO patent search.
 * Contains matching patent numbers in DOCDB format.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
public class EPOSearchOutput  {
    @LLMDescription("List of matching patent numbers in DOCDB format (e.g., EP.1000000.B1)")
    private List<String> patentNumbers;
    @LLMDescription("Total number of matches found in EPO")
    private Integer totalCount;
    public EPOSearchOutput() {
    }
    public List<String> getPatentNumbers() {
        return patentNumbers;
    }
    public void setPatentNumbers(List<String> patentNumbers) {
        this.patentNumbers = patentNumbers;
    }
    public Integer getTotalCount() {
        return totalCount;
    }
    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }
    public boolean hasResults() {
        return patentNumbers != null && !patentNumbers.isEmpty();
    }
}
