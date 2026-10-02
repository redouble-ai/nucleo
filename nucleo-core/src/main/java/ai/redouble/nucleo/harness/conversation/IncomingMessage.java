/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.errors.*;

import java.io.*;
import java.time.*;
import java.util.*;

/**
 * The message a model sends back: its text and content blocks, the handler that parses the
 * typed answer out of it, and the output token count the provider reported.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-21)
 */
public class IncomingMessage<T> implements Message, Serializable {
    private String messageId;
    private Instant timestamp;
    protected StringBuilder content;
    protected boolean enableCache = false;
    protected transient ResponseHandler<T> responseHandler;
    private Integer actualOutputTokens;
    private final String role = "assistant";
    private List<ContentBlock> contentBlocks;
    private boolean compactable = true;  // Whether this message can be compacted

    public IncomingMessage(ResponseHandler<T> responseHandler) {
        this.responseHandler = responseHandler;
        this.content = new StringBuilder();
    }
    // Core metadata methods
    public String getRole() {
        return role;
    }
    public String getMessageId() {
        return messageId;
    }
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
    public Instant getTimestamp() {
        return timestamp;
    }
    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
    // Response handler methods
    public ResponseHandler<T> getResponseHandler() {
        return responseHandler;
    }
    /**
     * Checks if this message has been dehydrated (ResponseHandler is null).
     * This typically happens after serialization and deserialization.
     */
    public boolean isDehydrated() {
        return responseHandler == null;
    }
    /**
     * Rehydrates this message by providing a new ResponseHandler.
     * Use this to restore parsing functionality after deserialization.
     */
    public void rehydrate(ResponseHandler<T> handler) {
        this.responseHandler = handler;
    }
    // Cache control
    public boolean isEnableCache() {
        return enableCache;
    }
    public void setCache(boolean enableCache) {
        this.enableCache = enableCache;
    }
    /**
     * Gets actual output tokens for this response.
     */
    public Integer getActualOutputTokens() {
        return actualOutputTokens;
    }

    /**
     * Sets actual output tokens from API response.
     */
    public void setActualOutputTokens(Integer outputTokens) {
        this.actualOutputTokens = outputTokens;
    }

    /**
     * Gets structured content blocks if available.
     */
    public List<ContentBlock> getContentBlocks() {
        return contentBlocks;
    }

    /**
     * Sets structured content blocks from provider client.
     */
    public void setContentBlocks(List<ContentBlock> blocks) {
        this.contentBlocks = blocks != null ? List.copyOf(blocks) : null;
    }

    /**
     * Checks if structured content blocks are available.
     */
    public boolean hasContentBlocks() {
        return contentBlocks != null && !contentBlocks.isEmpty();
    }

    // Content methods
    public String getRawContent() {
        if (content.isEmpty() && hasContentBlocks()) {
            return contentBlocksToText(contentBlocks);
        }
        return content.toString();
    }

    private String contentBlocksToText(List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock textBlock) {
                sb.append(textBlock.text());
            }
        }
        return sb.toString();
    }
    public void overwriteRawContent(String content) {
        this.content = new StringBuilder(content != null ? content : "");
    }
    /**
     * Gets the parsed response using the ResponseHandler.
     * Throws DehydratedException if the handler is null (after deserialization).
     *
     * @return the parsed response of type T
     * @throws IOException if parsing fails
     * @throws DehydratedException if the message is dehydrated
     */
    public T getResponse() throws IOException, LLMReadableCheckedException {
        if (isDehydrated()) {
            throw new DehydratedException(
                "Cannot parse response - message is dehydrated. " +
                "Call rehydrate() with appropriate ResponseHandler first."
            );
        }
        if (hasContentBlocks()) {
            return responseHandler.parse(getContentBlocks());
        } else {
            return responseHandler.parse(getRawContent());
        }
    }
    /**
     * Returns actual output tokens for this response.
     */
    @Override
    public Integer getActualTokens() {
        return actualOutputTokens;
    }

    /**
     * Checks if this message can be compacted during context window management.
     * Non-compactable messages (like final answers) are preserved verbatim.
     *
     * @return true if compactable, false to preserve verbatim
     */
    @Override
    public boolean isCompactable() {
        return compactable;
    }

    /**
     * Sets whether this message can be compacted.
     * Set to false for final assistant answers that should be preserved.
     *
     * @param compactable true if compactable, false to preserve verbatim
     */
    @Override
    public void setCompactable(boolean compactable) {
        this.compactable = compactable;
    }
}