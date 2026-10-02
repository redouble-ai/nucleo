/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit.models;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input for fetching full-text articles from PubMed Central.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-25)
 */
@LLMDescription("Parameters for fetching a full-text article from PubMed Central by PMCID")
public class PMCFullTextInput  {
    @LLMRequired
    @LLMDescription("PubMed Central ID (e.g. PMC1234567)")
    @LLMExample("PMC1234567")
    private String pmcid;

    public String getPmcid() {
        return pmcid;
    }

    public void setPmcid(String pmcid) {
        this.pmcid = pmcid;
    }
}