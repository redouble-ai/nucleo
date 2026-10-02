/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input for EPO patent claims retrieval.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
public class EPOClaimsInput  {
    @LLMRequired
    @LLMDescription("Patent number, dotted DOCDB as every EPO tool prints it (e.g., EP.1000000.B1), or EPODOC (EP1000000B1) with inputFormat epodoc")
    private String patentNumber;
    @LLMDescription("Input number format: docdb or epodoc (default: docdb)")
    private String inputFormat;
    public EPOClaimsInput() {
    }
    public String getPatentNumber() {
        return patentNumber;
    }
    public void setPatentNumber(String patentNumber) {
        this.patentNumber = patentNumber;
    }
    public String getInputFormat() {
        return inputFormat;
    }
    public void setInputFormat(String inputFormat) {
        this.inputFormat = inputFormat;
    }
}
