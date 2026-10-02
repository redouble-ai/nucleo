/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts.tools;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input for searching across artifact content.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
@LLMDescription("Parameters for searching across artifact content")
public class SearchArtifactContentInput  {
    @LLMRequired
    @LLMDescription("Exact search string for case-insensitive matching across artifact fields")
    private String query;

    @LLMDescription("Optional: limit search to specific artifact type (e.g., 'citation', 'webpage')")
    private String artifactType;

    @LLMDescription("Optional: limit search to a specific artifact, e.g. \u00ABartifact:link:cite~abc123\u00BB")
    private String artifactRef;

    @LLMDescription("Maximum number of matches to return, 1 or greater (default: 10)")
    private Integer maxResults;
    @LLMDescription("Characters of context to show around each match, 0 or greater (default: 200)")
    private Integer contextChars;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getArtifactType() {
        return artifactType;
    }

    public void setArtifactType(String artifactType) {
        this.artifactType = artifactType;
    }

    public String getArtifactRef() {
        return artifactRef;
    }

    public void setArtifactRef(String artifactRef) {
        this.artifactRef = artifactRef;
    }

    public Integer getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(Integer maxResults) {
        this.maxResults = maxResults;
    }

    public Integer getContextChars() {
        return contextChars;
    }

    public void setContextChars(Integer contextChars) {
        this.contextChars = contextChars;
    }
}
