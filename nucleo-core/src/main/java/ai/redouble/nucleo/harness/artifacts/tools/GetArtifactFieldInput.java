/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts.tools;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input for retrieving full content of a specific artifact field.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
@LLMDescription("Parameters for retrieving full content of an artifact field")
public class GetArtifactFieldInput  {
    @LLMRequired
    @LLMDescription("The artifact reference as shown in the registry, e.g. \u00ABartifact:link:cite~abc123\u00BB")
    private String artifactRef;

    @LLMRequired
    @LLMDescription("The field name to retrieve (e.g., 'content', 'abstractText', 'description')")
    private String fieldName;
    @LLMDescription("Start position for partial extraction (0-based). If set, returns content from this offset instead of full field.")
    private Integer offset;
    @LLMDescription("Maximum characters to return. Default: 10000. Use with offset for chunk-by-chunk reading.")
    private Integer maxChars;

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

    public Integer getOffset() {
        return offset;
    }

    public void setOffset(Integer offset) {
        this.offset = offset;
    }

    public Integer getMaxChars() {
        return maxChars;
    }

    public void setMaxChars(Integer maxChars) {
        this.maxChars = maxChars;
    }
}
