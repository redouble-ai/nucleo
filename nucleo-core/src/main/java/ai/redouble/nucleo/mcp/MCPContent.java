/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;


/**
 * Represents a content item in an MCP tool response.
 * Content can be text, image (base64), or resource reference.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
public class MCPContent {
    private String type;
    private String text;
    private String mimeType;
    private String data;

    public MCPContent() {
    }

    /**
     * Returns the content type: "text", "image", or "resource".
     */
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    /**
     * Returns the text content (for type="text").
     */
    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    /**
     * Returns the MIME type (for binary content).
     */
    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    /**
     * Returns the base64-encoded data (for binary content).
     */
    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    /**
     * Returns true if this is text content.
     */
    public boolean isText() {
        return "text".equals(type);
    }

    /**
     * Returns true if this is image content.
     */
    public boolean isImage() {
        return "image".equals(type);
    }

    /**
     * Returns true if this is a resource reference.
     */
    public boolean isResource() {
        return "resource".equals(type);
    }
}
