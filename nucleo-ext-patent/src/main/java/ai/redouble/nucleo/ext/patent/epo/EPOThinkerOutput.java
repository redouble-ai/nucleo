/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.ext.patent.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.thinking.*;

import java.util.*;

/**
 * Output from EPOThinker.
 * Contains patent artifacts with reasoning.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
public class EPOThinkerOutput extends ThinkerOutput<ChainOfThoughtReasoning> {
    @LLMRequired
    @LLMDescription("Summary of what was found and analysis of results")
    private String summary;
    @LLMRequired
    @LLMDescription("List of patent artifacts with full details")
    private List<PatentArtifact> patents;
    @LLMDescription("Total number of patents found matching the query")
    private Integer patentsFound;
    public EPOThinkerOutput() {
    }
    public String getSummary() {
        return summary;
    }
    public void setSummary(String summary) {
        this.summary = summary;
    }
    public List<PatentArtifact> getPatents() {
        return patents;
    }
    public void setPatents(List<PatentArtifact> patents) {
        this.patents = patents;
    }
    public Integer getPatentsFound() {
        return patentsFound;
    }
    public void setPatentsFound(Integer patentsFound) {
        this.patentsFound = patentsFound;
    }
}
