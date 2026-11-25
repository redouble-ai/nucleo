/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.annotation.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

/**
 * Snapshot of a ConversationContext for persistence.
 * Preserves all conversation state including messages and main objective blocks.
 *
 * <p>The transcript is stored as it was sent, a tool result with the references and
 * summaries the model saw. The artifacts are stored beside it whole, every field with its
 * full value and a list with every row, together with the summaries they keep. A prompt
 * object is stored with its artifacts as references and takes them back from the restored
 * registry. A store keeps the text {@link #toJson} gives and reads it with
 * {@link #fromJson}.
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
    // Seat declaration - what the conversation asks for, independent of any resolution. A
    // role is stored by its parts, so a restored conversation says what work it was doing
    // until the adopting thinker stamps its own declarations over it.
    private Grade grade;
    private String roleName;
    private String roleCatalogId;
    private Depth depth;
    private boolean interactive;
    // Artifact map for preserving artifacts across persistence.
    // Keys are artifact refs (e.g. «artifact:link:cite:pubmed~x1y2z3»), type resolved from the key.
    @JsonDeserialize(using = ArtifactMapDeserializer.class)
    private Map<String, Artifact> artifacts;
    /**
     * The summaries the conversation's artifacts keep, by artifact reference and then by
     * the key the summary is kept under. Saved so that a restored conversation shows each
     * long field as it showed it before the save: a summary a model wrote is different each
     * time it is made, and making it again costs a call.
     */
    private Map<String, Map<String, String>> artifactSummaries;
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
        // Blocks first: storing a prompt object registers the artifacts it holds, and the
        // registry is read after them
        ArtifactRegistry registry = context.getArtifactRegistry();
        // Preserve messages
        snapshot.messages = context.getMessages().stream()
            .map(message -> MessageSnapshot.fromMessage(message, registry))
            .collect(Collectors.toList());
        // Preserve the keyed objective map, in render order
        snapshot.mainObjective = new LinkedHashMap<>();
        context.getMainObjective().forEach((key, block) ->
            snapshot.mainObjective.put(key, ContentBlockSnapshot.fromBlock(block, registry)));
        // Preserve declared tools as converted definitions: a tool declared but never
        // rendered would otherwise vanish with the live object it was declared through
        snapshot.declaredTools = context.declaredToolDefinitions().stream()
            .map(block -> ContentBlockSnapshot.fromBlock(block, registry))
            .collect(Collectors.toList());
        // The serving spec when one is resolved, else whatever prior the context carries
        snapshot.modelIdentifier = context.isModelResolved() ? context.getModel().getId() : context.getPriorSpecId();
        snapshot.grade = context.getGrade();
        if (context.getRole() != null) {
            snapshot.roleName = context.getRole().name();
            snapshot.roleCatalogId = context.getRole().catalogId();
        }
        snapshot.depth = context.getDepth();
        snapshot.interactive = context.isInteractive();
        // Preserve artifacts: the top-level ones, each written whole with the artifacts it
        // holds, and the summaries of every artifact, the held ones included
        snapshot.artifacts = new HashMap<>(registry.getAllArtifacts());
        snapshot.artifactSummaries = new TreeMap<>();
        registry.getAllArtifactsIncludingReachable().forEach((ref, artifact) -> {
            Map<String, String> kept = artifact.getSummaries().all();
            if (!kept.isEmpty()) {
                snapshot.artifactSummaries.put(ref, kept);
            }
        });
        return snapshot;
    }

    /**
     * This snapshot as the text a store keeps: everything whole, the artifacts with their
     * full values. It is the one form a snapshot is stored in; a write that summarizes
     * ({@link NucleoJsonSerializer#writeSummarized}) would store summaries in place of
     * values, and {@link #fromJson} would read them back as the values.
     *
     * @return the snapshot as JSON
     */
    public String toJson() {
        return NucleoJsonSerializer.write(this);
    }

    /**
     * The snapshot a store kept as {@link #toJson} text.
     *
     * @param json the stored text
     * @throws IOException when the text is not a snapshot
     */
    public static ConversationPersistenceSnapshot fromJson(String json) throws IOException {
        return NucleoJsonSerializer.parse(json, ConversationPersistenceSnapshot.class);
    }
    public ConversationContext toConversation(ResponseHandler<?> responseHandler) {
        ConversationContext context = new ConversationContext(conversationId);
        context.setPriorSpecId(modelIdentifier);
        context.setGrade(grade);
        if (roleName != null) {
            context.setRole(new ModelRole(roleName, roleCatalogId, grade));
        }
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
        // Restore the artifact registry first: a stored prompt object holds its artifacts as
        // references, and they are taken from here as the blocks are restored
        if (artifacts != null) {
            ArtifactRegistry restoredRegistry = new ArtifactRegistry();
            for (Artifact artifact : artifacts.values()) {
                restoredRegistry.register(artifact);
            }
            context.setArtifactRegistry(restoredRegistry);
        }
        ArtifactRegistry registry = context.getArtifactRegistry();
        if (artifactSummaries != null) {
            artifactSummaries.forEach((ref, kept) -> {
                Artifact artifact = registry.get(ref);
                if (artifact != null) {
                    kept.forEach(artifact.getSummaries()::keep);
                }
            });
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
                        if ("tool_use".equals(block.getType())) {
                            requestedToolUseIds.add(block.getToolUseId());
                        }
                    }
                }
            }
            // A response handler says how the answer to a turn is parsed. Only a last turn
            // still waiting for its answer takes the handler the restore is given, and an
            // answer keeps it, since it is what the answer is read with. A turn followed by
            // the model's answer was asked for a reply and is restored as one: nothing is
            // parsed with it again, so it carries no handler, and it renders with the reply
            // line of the handler the conversation is asked with now, as it did before the
            // save. A turn followed by another turn (a summary, a message added while the
            // model worked) was never the one asked.
            for (int i = 0; i < messages.size(); i++) {
                MessageSnapshot msgSnapshot = messages.get(i);
                boolean earlierTurn = "outgoing".equals(msgSnapshot.getMessageType()) && i < messages.size() - 1;
                Message message = msgSnapshot.toMessage(earlierTurn ? null : responseHandler, requestedToolUseIds, registry);
                if (earlierTurn && "incoming".equals(messages.get(i + 1).getMessageType())) {
                    ((OutgoingMessage<?>) message).setAnsweredBeforeRestore(true);
                }
                context.addMessage(message);
            }
        }
        // Restore the objective. The persisted state is the truth for this conversation's
        // head, so the constructor-fresh map is nuked before the replay; the framework's
        // use-time puts refresh their slots on the next composition.
        if (mainObjective != null) {
            context.nukeMainObjective();
            mainObjective.forEach((key, blockSnapshot) ->
                context.putObjectiveBlock(key, blockSnapshot.toBlock(registry)));
        }
        else if (mainObjectiveBlocks != null) {
            // A snapshot from before the objective became a keyed map: anonymous blocks
            // replay under generated keys, and tool definitions that lived in the head
            // become declarations - the first render places them back in the head
            context.nukeMainObjective();
            int slot = 0;
            for (ContentBlockSnapshot blockSnapshot : mainObjectiveBlocks) {
                ContentBlock block = blockSnapshot.toBlock(registry);
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
                if (blockSnapshot.toBlock(registry) instanceof ToolDefinitionBlock td) {
                    context.addTool(td);
                }
            }
        }
        return context;
    }

    public Map<String, Map<String, String>> getArtifactSummaries() {
        return artifactSummaries;
    }

    public void setArtifactSummaries(Map<String, Map<String, String>> artifactSummaries) {
        this.artifactSummaries = artifactSummaries;
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