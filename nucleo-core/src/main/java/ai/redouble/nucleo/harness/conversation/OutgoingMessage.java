/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;

import java.io.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;

/**
 * Concrete outgoing message class for sending content to LLMs.
 * <p>
 * Content is stored as structured blocks (TextBlock, PojoBlock, ImageBlock, FileBlock)
 * allowing LLM clients to format them according to their API requirements.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-21)
 */
public class OutgoingMessage<T> implements Message, Serializable {
    private String messageId;
    private Instant timestamp;
    protected boolean enableCache = false;
    protected transient ResponseHandler<T> responseHandler;
    private String role = "user";
    private final List<ContentBlock> contentBlocks = new ArrayList<>();
    private transient String cachedRawContent = null;
    // Caps API max_tokens (via ConversationContext.resolveOutputBudget, which every
    // SDK client consumes) AND drives the local rate-limiter reservation. The same
    // value is used on both sides of the boundary so they stay in lock-step.
    private Integer requestedOutputTokens;
    private boolean compactable = true;  // Whether this message can be compacted
    private boolean appAuthored = false;  // Application-injected context on the user channel, not a human utterance

    public OutgoingMessage(ResponseHandler<T> responseHandler) {
        this.responseHandler = responseHandler;
    }

    // Core metadata methods
    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
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

    // Content addition methods

    /** Adds a text block; null or blank text is skipped - an empty block is not content. */
    public void addText(String text) {
        if (text != null && !text.trim().isEmpty()) {
            contentBlocks.add(new TextBlock(text));
            cachedRawContent = null;

        }
    }

    public void addPojo(Object pojo) {
        if (pojo != null) {
            contentBlocks.add(new PojoBlock(pojo));
            cachedRawContent = null;

        }
    }

    public void addToolDefinition(ToolDefinitionBlock tool) {
        if (tool != null) {
            contentBlocks.add(tool);
            cachedRawContent = null;
        }
    }

    public void addImage(InputStream imageStream, String mimeType, String description) throws IOException {
        if (imageStream != null) {
            byte[] imageBytes = imageStream.readAllBytes();
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            ImageBlock block = new ImageBlock(base64, mimeType, description);
            contentBlocks.add(block);
            cachedRawContent = null;
        }
    }

    public void addFile(InputStream fileStream, String mimeType, String filename) throws IOException {
        if (fileStream != null) {
            if (mimeType != null && mimeType.startsWith("text/")) {
                String text = new String(fileStream.readAllBytes(), StandardCharsets.UTF_8);
                StringBuilder fileContent = new StringBuilder();
                fileContent.append("File '").append(filename).append("':\n");
                fileContent.append(text);
                addText(fileContent.toString());
            }
            else {
                byte[] fileBytes = fileStream.readAllBytes();
                String base64 = Base64.getEncoder().encodeToString(fileBytes);
                FileBlock block = new FileBlock(base64, mimeType, filename);
                contentBlocks.add(block);
                cachedRawContent = null;
            }
        }
    }

    public void addError(Throwable error) {
        if (error != null) {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            StringBuilder errorContent = new StringBuilder();
            errorContent.append("Error: ").append(error.getMessage());
            errorContent.append("\n").append(sw);
            addText(errorContent.toString());
        }
    }

    public void addToolResult(String toolUseId, String resultJson, boolean isError) {
        if (toolUseId != null && resultJson != null) {
            contentBlocks.add(new ToolResultBlock(toolUseId, resultJson, isError));
            cachedRawContent = null;
        }
    }

    // Content retrieval methods

    /**
     * Gets all content blocks in the order they were added.
     * Clients can iterate these blocks and format according to their API requirements.
     */
    public List<ContentBlock> getContentBlocks() {
        return Collections.unmodifiableList(contentBlocks);
    }

    public String getRawContent() {
        if (cachedRawContent == null) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (ContentBlock block : contentBlocks) {
                if (!first && !(block instanceof ImageBlock) && !(block instanceof FileBlock)) {
                    sb.append("\n\n");
                }
                first = false;
                switch (block) {
                case TextBlock tb -> sb.append(tb.text());
                case PojoBlock pb -> {
                    if (pb.pojo() instanceof Artifact ap
                            && ap instanceof ai.redouble.nucleo.harness.artifacts.AbstractArtifact aa
                            && aa.hasReference()) {
                        sb.append("{\"@ref\":\"").append(aa.getArtifactRef()).append("\"}");
                    } else {
                        sb.append(ai.redouble.nucleo.harness.schema.NucleoJsonSerializer.writeSummarized(pb.pojo()));
                    }
                }
                case JsonBlock jb -> sb.append(jb.json());
                case ToolDefinitionBlock td ->
                    sb.append(ai.redouble.nucleo.harness.llm.encode.ToolDefinitionBlockEncoder.renderText(td));
                case ImageBlock ib -> {
                    if (ib.description() != null) {
                        sb.append("[Image: ").append(ib.description()).append("]");
                    }
                }
                case FileBlock fb -> {
                    sb.append("[File: ").append(fb.filename()).append("]");
                }
                case ToolUseBlock tu -> {
                    sb.append("[Tool Use: ").append(tu.toolName()).append(" (").append(tu.toolUseId()).append(")]");
                }
                case ToolResultBlock tr -> {
                    sb.append("[Tool Result: ").append(tr.toolUseId()).append("]");
                }
                case ThinkingBlock th -> {
                    // Thinking blocks are assistant-only. If an outgoing ever carries one, render a placeholder.
                    sb.append("[Thinking]");
                }
                case RedactedThinkingBlock rt -> {
                    sb.append("[Redacted Thinking]");
                }
                case SkillBlock sb2 -> {
                    sb.append("[Skill: ").append(sb2.skill().name()).append("]");
                }
                }
            }
            cachedRawContent = sb.toString();
        }
        return cachedRawContent;
    }

    // Multimodal content getters
    public List<ImageBlock> getImageBlocks() {
        return contentBlocks.stream().filter(ImageBlock.class::isInstance).map(ImageBlock.class::cast).toList();
    }

    public List<FileBlock> getFileBlocks() {
        return contentBlocks.stream().filter(FileBlock.class::isInstance).map(FileBlock.class::cast).toList();
    }

    /**
     * Creates an incoming message for the response to this outgoing message.
     */
    public IncomingMessage<T> createIncomingMessage() {
        return new IncomingMessage<>(responseHandler);
    }

    /**
     * Always returns null. OutgoingMessage uses estimates only.
     * Actual input tokens from API are conversation-level, not per-message.
     */
    @Override
    public Integer getActualTokens() {
        return null;
    }

    /**
     * Gets requested output tokens for rate limiter reservation.
     */
    @Override
    public Integer getRequestedOutputTokens() {
        return requestedOutputTokens;
    }

    /**
     * Sets the output token budget for this call. Consumed by
     * {@link ai.redouble.nucleo.harness.conversation.ConversationContext#resolveOutputBudget()},
     * which every SDK client uses as the API {@code max_tokens} field; the same
     * value also drives the local rate-limiter reservation via
     * {@link ai.redouble.nucleo.tools.thinking.LLMCall#getRequirements()}. Caps the response size
     * upstream and pre-debits the matching tokens locally.
     */
    public void setRequestedOutputTokens(Integer tokens) {
        this.requestedOutputTokens = tokens;
    }

    /**
     * Checks if this message can be compacted during context window management.
     * Non-compactable messages (like user inputs and final answers) are preserved verbatim.
     *
     * @return true if compactable, false to preserve verbatim
     */
    @Override
    public boolean isCompactable() {
        return compactable;
    }

    /**
     * Sets whether this message can be compacted.
     * Set to false for user messages and final assistant answers that should be preserved.
     *
     * @param compactable true if compactable, false to preserve verbatim
     */
    @Override
    public void setCompactable(boolean compactable) {
        this.compactable = compactable;
    }

    @Override
    public boolean isAppAuthored() {
        return appAuthored;
    }

    @Override
    public void setAppAuthored(boolean appAuthored) {
        this.appAuthored = appAuthored;
    }
}