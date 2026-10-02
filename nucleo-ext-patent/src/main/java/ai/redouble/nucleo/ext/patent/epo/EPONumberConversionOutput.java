/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Output from EPO patent number format conversion.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
public class EPONumberConversionOutput  {
    @LLMDescription("Converted patent number in the requested output format")
    private String convertedNumber;
    public EPONumberConversionOutput() {
    }
    public String getConvertedNumber() {
        return convertedNumber;
    }
    public void setConvertedNumber(String convertedNumber) {
        this.convertedNumber = convertedNumber;
    }
}
