/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.models.*;
import com.fasterxml.jackson.databind.annotation.*;

import java.time.*;
import java.util.*;
import java.util.stream.*;

/**
 * Snapshot of a ConversationContext for persistence.
 * Preserves all conversation state including messages and main objective blocks.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-15)
 */
public class ConversationPersistenceSnapshot  {
    private String conversationId;
    /**
     * Provenance: the workflow of the turn that last saved this snapshot. A restored
     * context carries it only until acquisition, which stamps the acquiring turn's
     * own workflowId.
     */
    private String workflowId;
    private String title;
    private List<MessageSnapshot> messages;
    /** The keyed objective map, in render order. */
    private LinkedHashMap<String, ContentBlockSnapshot> mainObjective;
    /** Declared tools as converted definitions - conversion happens right before serialization. */
    private List<ContentBlockSnapshot> declaredTools;
    /**
     * Objective of a snapshot written before the objective became a keyed map: an anonymous
     * block list. Read-only; never written. Restore replays it under generated keys.
     */
    private List<ContentBlockSnapshot> mainObjectiveBlocks;
    private boolean compactable;
    private boolean temporary;
    private boolean cacheMainObjective;
    private Instant createdAt;
    private Instant lastUpdatedAt;
    /**
     * Id of the spec that served this conversation - restored as the PRIOR, the
     * stickiness and compaction-accounting ingredient. The restored conversation itself
     * is unbound until a job wires a fresh binding.
     */
    private String modelIdentifier;
    // Seat declaration - what the conversation asks for, independent of any resolution.
    private Grade grade;
    private Depth depth;
    private boolean interactive;
    // Artifact map for preserving artifacts across persistence.
    // Keys are artifact refs (e.g. «artifact:link:cite:pubmed~x1y2z3»), type resolved from the key.
    @JsonDeserialize(using = ArtifactMapDeserializer.class)
    private Map<String, Artifact> artifacts;
    public ConversationPersistenceSnapshot() {}
    public static ConversationPersistenceSnapshot fromConversation(ConversationContext context) {
        ConversationPersistenceSnapshot snapshot = new ConversationPersistenceSnapshot();
        snapshot.conversationId = context.getConversationId();
        snapshot.workflowId = context.getWorkflowId();
        snapshot.title = context.getTitle();
        snapshot.compactable = context.isCompactable();
        snapshot.temporary = context.isTemporary();
        snapshot.cacheMainObjective = context.isCacheMainObjective();
        snapshot.createdAt = context.getCreatedAt();
        snapshot.lastUpdatedAt = Instant.now();
        // Preserve messages
        snapshot.messages = context.getMessages().stream()
            .map(MessageSnapshot::fromMessage)
            .collect(Collectors.toList());
        // Preserve the keyed objective map, in render order
        snapshot.mainObjective = new LinkedHashMap<>();
        context.getMainObjective().forEach((key, block) ->
            snapshot.mainObjective.put(key, ContentBlockSnapshot.fromBlock(block)));
        // Preserve declared tools as converted definitions: a tool declared but never
        // rendered would otherwise vanish with the live object it was declared through
        snapshot.declaredTools = context.declaredToolDefinitions().stream()
            .map(ContentBlockSnapshot::fromBlock)
            .collect(Collectors.toList());
        // The serving spec when one is resolved, else whatever prior the context carries
        snapshot.modelIdentifier = context.isModelResolved() ? context.getModel().getId() : context.getPriorSpecId();
        snapshot.grade = context.getGrade();
        snapshot.depth = context.getDepth();
        snapshot.interactive = context.isInteractive();
        // Preserve artifacts
        ArtifactRegistry registry = context.getArtifactRegistry();
        if (registry != null) {
            snapshot.artifacts = new HashMap<>(registry.getAllArtifacts());
        }
        return snapshot;
    }
    public ConversationContext toConversation(ResponseHandler<?> responseHandler) {
        ConversationContext context = new ConversationContext(conversationId);
        context.setPriorSpecId(modelIdentifier);
        context.setGrade(grade);
        context.setDepth(depth);
        context.setInteractive(interactive);
        context.setWorkflowId(workflowId);
        context.setTitle(title);
        context.setCompactable(compactable);
        context.setTemporary(temporary);
        context.setCacheMainObjective(cacheMainObjective);
        if (createdAt != null) {
            context.setCreatedAt(createdAt);
        }
        // Restore messages, dropping any tool result whose request is not in the transcript.
        //
        // A provider refuses the WHOLE conversation when it finds one ("each tool_result block must
        // have a corresponding tool_use block in the previous message"), so a single orphan makes a
        // stored conversation permanently unanswerable rather than merely incomplete. Snapshots
        // written before incoming messages carried their blocks hold nothing but orphans, and this is
        // what lets those conversations continue at all.
        if (messages != null) {
            Set<String> requestedToolUseIds = new HashSet<>();
            for (MessageSnapshot msgSnapshot : messages) {
                if (msgSnapshot.getContentBlocks() != null) {
                    for (ContentBlockSnapshot block : msgSnapshot.getContentBlocks()) {
                        if (block.toBlock() instanceof ToolUseBlock toolUse) {
                            requestedToolUseIds.add(toolUse.toolUseId());
                        }
                    }
                }
            }
            for (MessageSnapshot msgSnapshot : messages) {
                context.addMessage(msgSnapshot.toMessage(responseHandler, requestedToolUseIds));
            }
        }
        // Restore the objective. The persisted state is the truth for this conversation's
        // head, so the constructor-fresh map is nuked before the replay; the framework's
        // use-time puts refresh their slots on the next composition.
        if (mainObjective != null) {
            context.nukeMainObjective();
            mainObjective.forEach((key, blockSnapshot) ->
                context.putObjectiveBlock(key, blockSnapshot.toBlock()));
        }
        else if (mainObjectiveBlocks != null) {
            // A snapshot from before the objective became a keyed map: anonymous blocks
            // replay under generated keys, and tool definitions that lived in the head
            // become declarations - the first render places them back in the head
            context.nukeMainObjective();
            int slot = 0;
            for (ContentBlockSnapshot blockSnapshot : mainObjectiveBlocks) {
                ContentBlock block = blockSnapshot.toBlock();
                if (block instanceof ToolDefinitionBlock td) {
                    context.addTool(td);
                }
                else {
                    context.putObjectiveBlock("objective-" + slot++, block);
                }
            }
        }
        if (declaredTools != null) {
            for (ContentBlockSnapshot blockSnapshot : declaredTools) {
                if (blockSnapshot.toBlock() instanceof ToolDefinitionBlock td) {
                    context.addTool(td);
                }
            }
        }
        // Restore artifact registry
        if (artifacts != null) {
            ArtifactRegistry restoredRegistry = new ArtifactRegistry();
            for (Artifact artifact : artifacts.values()) {
                restoredRegistry.register(artifact);
            }
            context.setArtifactRegistry(restoredRegistry);
        }
        return context;
    }
    // Getters and setters
    public String getConversationId() {
        return conversationId;
    }
    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }
    public String getWorkflowId() {
        return workflowId;
    }
    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }
    public String getTitle() {
        return title;
    }
    public void setTitle(String title) {
        this.title = title;
    }
    public List<MessageSnapshot> getMessages() {
        return messages;
    }
    public void setMessages(List<MessageSnapshot> messages) {
        this.messages = messages;
    }
    public LinkedHashMap<String, ContentBlockSnapshot> getMainObjective() {
        return mainObjective;
    }
    public void setMainObjective(LinkedHashMap<String, ContentBlockSnapshot> mainObjective) {
        this.mainObjective = mainObjective;
    }
    public List<ContentBlockSnapshot> getDeclaredTools() {
        return declaredTools;
    }
    public void setDeclaredTools(List<ContentBlockSnapshot> declaredTools) {
        this.declaredTools = declaredTools;
    }
    public List<ContentBlockSnapshot> getMainObjectiveBlocks() {
        return mainObjectiveBlocks;
    }
    public void setMainObjectiveBlocks(List<ContentBlockSnapshot> mainObjectiveBlocks) {
        this.mainObjectiveBlocks = mainObjectiveBlocks;
    }
    public boolean isCompactable() {
        return compactable;
    }
    public void setCompactable(boolean compactable) {
        this.compactable = compactable;
    }
    public boolean isTemporary() {
        return temporary;
    }
    public void setTemporary(boolean temporary) {
        this.temporary = temporary;
    }
    public boolean isCacheMainObjective() {
        return cacheMainObjective;
    }
    public void setCacheMainObjective(boolean cacheMainObjective) {
        this.cacheMainObjective = cacheMainObjective;
    }
    public Instant getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }
    public void setLastUpdatedAt(Instant lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }
    public String getModelIdentifier() {
        return modelIdentifier;
    }
    public void setModelIdentifier(String modelIdentifier) {
        this.modelIdentifier = modelIdentifier;
    }
    public Map<String, Artifact> getArtifacts() {
        return artifacts;
    }
    public void setArtifacts(Map<String, Artifact> artifacts) {
        this.artifacts = artifacts;
    }
}