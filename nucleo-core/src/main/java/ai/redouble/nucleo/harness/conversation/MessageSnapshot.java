/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

/**
 * Snapshot of a Message for persistence.
 * Handles both OutgoingMessage and IncomingMessage.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-15)
 */
public class MessageSnapshot  {
    private String messageType; // "outgoing" or "incoming"
    private String role;
    private String messageId;
    private Instant timestamp;
    private boolean enableCache;
    private boolean compactable = true;  // Whether this message can be compacted
    private boolean appAuthored = false;  // Application-injected context on the user channel
    // For OutgoingMessage
    private List<ContentBlockSnapshot> contentBlocks;
    private Integer requestedOutputTokens;
    // For IncomingMessage
    private String rawContent;
    private Integer actualOutputTokens;
    public MessageSnapshot() {}
    public static MessageSnapshot fromMessage(Message message) {
        MessageSnapshot snapshot = new MessageSnapshot();
        snapshot.role = message.getRole();
        snapshot.messageId = message.getMessageId();
        snapshot.timestamp = message.getTimestamp();
        snapshot.enableCache = message.isEnableCache();
        snapshot.compactable = message.isCompactable();
        snapshot.appAuthored = message.isAppAuthored();
        if (message instanceof OutgoingMessage<?> outgoing) {
            snapshot.messageType = "outgoing";
            snapshot.requestedOutputTokens = outgoing.getRequestedOutputTokens();
            // Convert ContentBlocks to snapshots
            snapshot.contentBlocks = outgoing.getContentBlocks().stream()
                .map(ContentBlockSnapshot::fromBlock)
                .collect(Collectors.toList());
        } else if (message instanceof IncomingMessage<?> incoming) {
            snapshot.messageType = "incoming";
            snapshot.rawContent = incoming.getRawContent();
            snapshot.actualOutputTokens = incoming.getActualOutputTokens();
            // The assistant's blocks, tool_use among them. Persisting only rawContent lost every
            // tool_use while the NEXT message's tool_result blocks were persisted faithfully, so a
            // restored conversation held results referring to requests that no longer existed and the
            // provider refused the following turn outright: "unexpected tool_use_id found in
            // tool_result blocks - each tool_result must have a corresponding tool_use in the previous
            // message". Any conversation whose turn called a tool could not be continued.
            if (incoming.hasContentBlocks()) {
                snapshot.contentBlocks = incoming.getContentBlocks().stream()
                    .map(ContentBlockSnapshot::fromBlock)
                    .collect(Collectors.toList());
            }
        } else {
            throw new IllegalArgumentException("Unknown message type: " + message.getClass());
        }
        return snapshot;
    }
    public Message toMessage(ResponseHandler<?> responseHandler) {
        return toMessage(responseHandler, null);
    }

    /**
     * @param knownToolUseIds the tool_use ids present anywhere in the transcript being restored, or
     *                        null to restore every block as stored. A tool_result whose id is absent
     *                        is dropped: the provider refuses the entire conversation over one such
     *                        orphan, so keeping it would make the conversation unanswerable rather
     *                        than merely incomplete.
     */
    public Message toMessage(ResponseHandler<?> responseHandler, Set<String> knownToolUseIds) {
        if ("outgoing".equals(messageType)) {
            OutgoingMessage<?> message = new OutgoingMessage<>(responseHandler);
            message.setRole(role);
            message.setMessageId(messageId);
            message.setTimestamp(timestamp);
            message.setCache(enableCache);
            message.setCompactable(compactable);
            message.setAppAuthored(appAuthored);
            message.setRequestedOutputTokens(requestedOutputTokens);
            // Reconstruct content blocks
            if (contentBlocks != null) {
                for (ContentBlockSnapshot blockSnapshot : contentBlocks) {
                    ContentBlock block = blockSnapshot.toBlock();
                    // Add blocks based on type
                    switch (block) {
                        case TextBlock tb -> message.addText(tb.text());
                        case JsonBlock jb -> {
                            // JSON blocks are treated as text for now
                            message.addText(jb.json());
                        }
                        case PojoBlock pb -> message.addPojo(pb.pojo());
                        case ToolDefinitionBlock td -> message.addToolDefinition(td);
                        case ImageBlock ib -> {
                            try {
                                // Convert base64 back to InputStream
                                byte[] imageBytes = Base64.getDecoder().decode(ib.base64());
                                ByteArrayInputStream stream = new ByteArrayInputStream(imageBytes);
                                message.addImage(stream, ib.mimeType(), ib.description());
                            } catch (IOException e) {
                                // Should not happen with ByteArrayInputStream
                                throw new RuntimeException("Failed to restore image block", e);
                            }
                        }
                        case FileBlock fb -> {
                            try {
                                // Convert base64 back to InputStream
                                byte[] fileBytes = Base64.getDecoder().decode(fb.base64());
                                ByteArrayInputStream stream = new ByteArrayInputStream(fileBytes);
                                message.addFile(stream, fb.mimeType(), fb.filename());
                            } catch (IOException e) {
                                // Should not happen with ByteArrayInputStream
                                throw new RuntimeException("Failed to restore file block", e);
                            }
                        }
                        case ToolUseBlock tu -> {
                        }
                        case ToolResultBlock tr -> {
                            if (knownToolUseIds == null || knownToolUseIds.contains(tr.toolUseId())) {
                                message.addToolResult(tr.toolUseId(), tr.resultJson(), tr.isError());
                            }
                        }
                        case ThinkingBlock th -> {
                            // Thinking blocks only appear on incoming (assistant) messages; outgoing never carries them.
                        }
                        case RedactedThinkingBlock rt -> {
                            // Same as ThinkingBlock: outgoing-only path, ignore.
                        }
                        case SkillBlock sb -> {
                            // SkillBlock lives on ConversationContext.loadedSkills, not message content;
                            // if a snapshot carries one on a message, restore by name into the conversation
                            // when available, otherwise drop silently here (snapshot-safe).
                        }
                    }
                }
            }
            return message;
        } else if ("incoming".equals(messageType)) {
            IncomingMessage<?> message = new IncomingMessage<>(responseHandler);
            message.setMessageId(messageId);
            message.setTimestamp(timestamp);
            message.setCache(enableCache);
            message.setCompactable(compactable);
            message.overwriteRawContent(rawContent);
            message.setActualOutputTokens(actualOutputTokens);
            // Restored so the tool_use blocks are present for the tool_result blocks that follow.
            // Older snapshots carry none; those conversations restore as text alone, exactly as before.
            if (contentBlocks != null && !contentBlocks.isEmpty()) {
                message.setContentBlocks(contentBlocks.stream()
                        .map(ContentBlockSnapshot::toBlock)
                        .collect(Collectors.toList()));
            }
            return message;
        } else {
            throw new IllegalStateException("Unknown message type: " + messageType);
        }
    }
    // Getters and setters
    public String getMessageType() {
        return messageType;
    }
    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }
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
    public boolean isEnableCache() {
        return enableCache;
    }
    public void setEnableCache(boolean enableCache) {
        this.enableCache = enableCache;
    }
    public boolean isCompactable() {
        return compactable;
    }
    public void setCompactable(boolean compactable) {
        this.compactable = compactable;
    }
    public boolean isAppAuthored() {
        return appAuthored;
    }
    public void setAppAuthored(boolean appAuthored) {
        this.appAuthored = appAuthored;
    }
    public List<ContentBlockSnapshot> getContentBlocks() {
        return contentBlocks;
    }
    public void setContentBlocks(List<ContentBlockSnapshot> contentBlocks) {
        this.contentBlocks = contentBlocks;
    }
    public Integer getRequestedOutputTokens() {
        return requestedOutputTokens;
    }
    public void setRequestedOutputTokens(Integer requestedOutputTokens) {
        this.requestedOutputTokens = requestedOutputTokens;
    }
    public String getRawContent() {
        return rawContent;
    }
    public void setRawContent(String rawContent) {
        this.rawContent = rawContent;
    }
    public Integer getActualOutputTokens() {
        return actualOutputTokens;
    }
    public void setActualOutputTokens(Integer actualOutputTokens) {
        this.actualOutputTokens = actualOutputTokens;
    }
}