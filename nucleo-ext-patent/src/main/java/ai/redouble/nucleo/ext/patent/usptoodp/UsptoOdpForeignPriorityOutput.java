/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.usptoodp;

import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Output containing USPTO ODP foreign priority data.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class UsptoOdpForeignPriorityOutput {
    @LLMDescription("Patent number")
    private String patentNumber;
    @LLMDescription("Earliest foreign priority date (YYYY-MM-DD)")
    private String priorityDate;
    @LLMDescription("Foreign priority claims (format: office - date - appNumber)")
    private List<String> priorityClaims;
    public UsptoOdpForeignPriorityOutput() {
    }
    public String getPatentNumber() {
        return patentNumber;
    }
    public void setPatentNumber(String patentNumber) {
        this.patentNumber = patentNumber;
    }
    public String getPriorityDate() {
        return priorityDate;
    }
    public void setPriorityDate(String priorityDate) {
        this.priorityDate = priorityDate;
    }
    public List<String> getPriorityClaims() {
        return priorityClaims;
    }
    public void setPriorityClaims(List<String> priorityClaims) {
        this.priorityClaims = priorityClaims;
    }
}
