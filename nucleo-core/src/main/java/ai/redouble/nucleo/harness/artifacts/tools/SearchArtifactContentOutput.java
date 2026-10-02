/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts.tools;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.thinking.*;

import java.util.*;

/**
 * Output containing search results across artifact content.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
@LLMDescription("Results of searching artifact content")
public class SearchArtifactContentOutput extends ThinkerOutput<SimpleReasoning> {
    @LLMDescription("List of search matches with context")
    private List<SearchMatch> matches;

    @LLMDescription("Total number of matches found (may be limited by maxResults)")
    private int totalMatches;

    public SearchArtifactContentOutput() {
        this.matches = new ArrayList<>();
    }

    public List<SearchMatch> getMatches() {
        return matches;
    }

    public void setMatches(List<SearchMatch> matches) {
        this.matches = matches;
    }

    public int getTotalMatches() {
        return totalMatches;
    }

    public void setTotalMatches(int totalMatches) {
        this.totalMatches = totalMatches;
    }
}
