/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Output containing backward citations parsed from EPO bibliographic data.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class EPOCitationsOutput {
    @LLMDescription("Patent number this citation data belongs to")
    private String patentNumber;
    @LLMDescription("Patent numbers cited by this patent (prior art)")
    private List<String> backwardCitations;
    public EPOCitationsOutput() {
    }
    public String getPatentNumber() {
        return patentNumber;
    }
    public void setPatentNumber(String patentNumber) {
        this.patentNumber = patentNumber;
    }
    public List<String> getBackwardCitations() {
        return backwardCitations;
    }
    public void setBackwardCitations(List<String> backwardCitations) {
        this.backwardCitations = backwardCitations;
    }
}
