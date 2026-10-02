/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts.tools;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.thinking.*;

/**
 * Output containing full content of an artifact field.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
@LLMDescription("Result of retrieving artifact field content")
public class GetArtifactFieldOutput extends ThinkerOutput<SimpleReasoning> {
    @LLMDescription("The full content of the requested field")
    private String content;

    @LLMDescription("The field name that was retrieved")
    private String fieldName;

    @LLMDescription("Character count of the content")
    private int length;
    @LLMDescription("True if the returned content was truncated (total length > maxChars)")
    private boolean truncated;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }
}
