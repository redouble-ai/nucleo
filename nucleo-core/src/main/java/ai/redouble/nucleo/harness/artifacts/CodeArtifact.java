/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;
import java.util.*;

/**
 * Artifact representing a code snippet or program.
 *
 * <p>When serialized to LLM, instances will be replaced with references like:
 * «artifact:code~g7h8i9»
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-13)
 */
@TypeAlias("code")
public class CodeArtifact extends AbstractArtifact {
    @LLMDescription("Programming language (e.g., 'java', 'python', 'javascript')")
    private String language;

    @LLMDescription("The actual code content")
    @LLMSummarizable(staticSummary = "source code")
    private String content;

    @LLMDescription("Brief description of what the code does")
    private String description;

    @LLMDescription("File name or path if applicable")
    private String fileName;

    @LLMDescription("Whether this is a complete file or a snippet")
    private Boolean isComplete;

    @LLMDescription("Line numbers in the original file (e.g., '45-67')")
    private String lineRange;

    @LLMDescription("Dependencies or imports required")
    private List<String> dependencies;

    @LLMDescription("Code type (e.g., 'class', 'function', 'snippet', 'configuration')")
    private String codeType;

    public CodeArtifact() {
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Boolean getIsComplete() {
        return isComplete;
    }

    public void setIsComplete(Boolean isComplete) {
        this.isComplete = isComplete;
    }

    public String getLineRange() {
        return lineRange;
    }

    public void setLineRange(String lineRange) {
        this.lineRange = lineRange;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<String> dependencies) {
        this.dependencies = dependencies;
    }

    public String getCodeType() {
        return codeType;
    }

    public void setCodeType(String codeType) {
        this.codeType = codeType;
    }

    /**
     * Returns a display label for the code artifact.
     * Format: [language] type: description (or fileName if no description)
     */
    public String getLabel() {
        StringBuilder sb = new StringBuilder();
        if (language != null) {
            sb.append("[").append(language).append("] ");
        }
        if (codeType != null) {
            sb.append(codeType).append(": ");
        }
        if (description != null) {
            sb.append(description);
        } else if (fileName != null) {
            sb.append(fileName);
        } else {
            sb.append("Code snippet");
        }
        if (lineRange != null) {
            sb.append(" (lines ").append(lineRange).append(")");
        }
        return sb.toString();
    }
}