/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts.tools;

import ai.redouble.nucleo.harness.schema.*;

/**
 * A single search match result.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
@LLMDescription("A match found during artifact content search")
public class SearchMatch  {
    @LLMDescription("The artifact reference containing the match")
    private String artifactRef;

    @LLMDescription("The field path where match was found (e.g., 'abstractText', 'metadata.description')")
    private String fieldName;

    @LLMDescription("Text snippet with match and surrounding context")
    private String snippet;

    @LLMDescription("Character position of match in the field")
    private int position;

    public String getArtifactRef() {
        return artifactRef;
    }

    public void setArtifactRef(String artifactRef) {
        this.artifactRef = artifactRef;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }
}
