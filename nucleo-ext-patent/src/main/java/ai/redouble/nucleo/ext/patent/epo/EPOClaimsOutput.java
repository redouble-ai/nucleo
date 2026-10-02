/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Output from EPO patent claims retrieval.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
public class EPOClaimsOutput  {
    @LLMDescription("Full text of the patent claims")
    @LLMSummarizable(value = "patent claims from EPO", size = SummarySize.PARAGRAPHS)
    private String claims;
    public EPOClaimsOutput() {
    }
    public String getClaims() {
        return claims;
    }
    public void setClaims(String claims) {
        this.claims = claims;
    }
}
