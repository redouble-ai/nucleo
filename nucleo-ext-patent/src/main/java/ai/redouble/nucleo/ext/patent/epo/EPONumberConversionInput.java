/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input for EPO patent number format conversion.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
public class EPONumberConversionInput  {
    @LLMRequired
    @LLMDescription("Patent number to convert")
    private String patentNumber;
    @LLMDescription("Input number format: docdb or epodoc (default: docdb)")
    private String inputFormat;
    @LLMDescription("Desired output format: docdb or epodoc (default: epodoc)")
    private String outputFormat;
    public EPONumberConversionInput() {
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
    public String getOutputFormat() {
        return outputFormat;
    }
    public void setOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat;
    }
}
