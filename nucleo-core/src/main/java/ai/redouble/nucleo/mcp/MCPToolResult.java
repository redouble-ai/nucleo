/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import ai.redouble.nucleo.harness.artifacts.*;
import com.fasterxml.jackson.annotation.*;

import java.util.*;

/**
 * Result from an MCP tool call.
 *
 * <p>{@code content} is the raw MCP content array kept for direct callers - typed tool
 * wrappers that extract data from it programmatically and never reach Jackson. It is
 * {@code @JsonIgnore}'d so LLM-facing serialization sees only {@code resultArtifact} -
 * a single artifact representation prevents the LLM from seeing both an inlined content
 * list and a ref to the same payload.
 *
 * <p>{@code resultArtifact} is an {@link Artifact}, not an {@link MCPArtifact}: a server
 * that is itself a redouble process serves artifacts that rebuild into their own types
 * (see {@link MCPArtifactRehydrator}), and the generic {@code MCPArtifact} is what an
 * untyped payload degrades to rather than what every payload is.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
public class MCPToolResult  {
    @JsonIgnore
    private List<MCPContent> content;
    private Artifact resultArtifact;

    public MCPToolResult() {
    }

    public List<MCPContent> getContent() {
        return content;
    }

    public void setContent(List<MCPContent> content) {
        this.content = content;
    }

    public Artifact getResultArtifact() {
        return resultArtifact;
    }

    public void setResultArtifact(Artifact resultArtifact) {
        this.resultArtifact = resultArtifact;
    }

    /**
     * Extracts the first text content from the result.
     * Returns null if no text content is present.
     */
    public String getTextContent() {
        if (content == null) {
            return null;
        }
        for (MCPContent item : content) {
            if (item.isText() && item.getText() != null) {
                return item.getText();
            }
        }
        return null;
    }

    /**
     * Returns all text content concatenated.
     */
    public String getAllTextContent() {
        if (content == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (MCPContent item : content) {
            if (item.isText() && item.getText() != null) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(item.getText());
            }
        }
        return !sb.isEmpty() ? sb.toString() : null;
    }
}
