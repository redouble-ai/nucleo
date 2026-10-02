/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit.models;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.thinking.*;

/**
 * Input parameters for the PaperAggregator agent.
 * Specifies the research question and constraints for literature synthesis.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-03)
 */
@LLMDescription("Parameters for autonomous literature aggregation and synthesis from biomedical databases")
public class PaperAggregatorInput extends ThinkerInput {

    @LLMDescription("Maximum number of articles to analyze in detail (default: agent decides, typically 10-20 for comprehensive coverage)")
    @LLMExample("15")
    private Integer maxArticlesToAnalyze;

    @LLMDescription("Start date for filtering results (YYYY/MM/DD or YYYY/MM or YYYY format)")
    @LLMExample("2020")
    private String dateFrom;

    @LLMDescription("End date for filtering results (YYYY/MM/DD or YYYY/MM or YYYY format)")
    @LLMExample("2024")
    private String dateTo;

    @LLMDescription("Filter by publication type (e.g., 'Review', 'Clinical Trial', 'Meta-Analysis', 'Randomized Controlled Trial')")
    @LLMExample("Clinical Trial")
    private String publicationType;

    public Integer getMaxArticlesToAnalyze() {
        return maxArticlesToAnalyze;
    }

    public void setMaxArticlesToAnalyze(Integer maxArticlesToAnalyze) {
        this.maxArticlesToAnalyze = maxArticlesToAnalyze;
    }

    public String getDateFrom() {
        return dateFrom;
    }

    public void setDateFrom(String dateFrom) {
        this.dateFrom = dateFrom;
    }

    public String getDateTo() {
        return dateTo;
    }

    public void setDateTo(String dateTo) {
        this.dateTo = dateTo;
    }

    public String getPublicationType() {
        return publicationType;
    }

    public void setPublicationType(String publicationType) {
        this.publicationType = publicationType;
    }
}
