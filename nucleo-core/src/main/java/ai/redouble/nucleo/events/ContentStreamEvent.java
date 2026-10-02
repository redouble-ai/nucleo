/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.llm.*;
import com.fasterxml.jackson.annotation.*;

import java.util.*;

/**
 * Event for streaming LLM content chunks to users.
 *
 * <p>Provides real-time delivery of generated content with automatic lifecycle phase mapping:
 * middle chunks use {@link MsgType#PROGRESSING} under the title {@code Streaming Content}, the
 * final chunk uses {@link MsgType#COMPLETING} under {@code Streaming Complete} and is 100%.
 * The human message is the chunk's content, else {@code Streaming...} or, on the last chunk,
 * {@code Stream completed}. The log message is {@code Streaming chunk: <first fifty
 * characters>}, or {@code Streaming complete} on the last chunk.
 *
 * <p><b>JSON Structure:</b> the progress event's fields plus a {@code chunk} object
 * containing content text, token count, and completion flag, and {@code artifacts} on any
 * chunk given some; the artifacts are copied at construction and read as null when none
 * were given.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-11)
 */
public class ContentStreamEvent extends JobProgressEvent<StreamChunk> {
    private final Map<String, Artifact> artifacts;

    /**
     * Creates a content stream event from a snapshot.
     *
     * @param snapshot the job snapshot
     * @param chunk the stream chunk
     */
    public ContentStreamEvent(JobSnapshot snapshot, StreamChunk chunk) {
        this(snapshot, chunk, null);
    }

    /**
     * Creates a content stream event with artifacts.
     *
     * @param snapshot the job snapshot
     * @param chunk the stream chunk
     * @param artifacts artifacts to include, on any chunk; null or empty for none
     */
    public ContentStreamEvent(JobSnapshot snapshot, StreamChunk chunk, Map<String, Artifact> artifacts) {
        super(snapshot, chunk);
        this.artifacts = artifacts != null && !artifacts.isEmpty() ? new HashMap<>(artifacts) : null;
        setMessage(generateMessage(chunk));
        if (chunk.isLast()) {
            setProgressPercent(100);
        }
    }

    /**
     * Gets the stream chunk.
     * Convenience method that returns the typed payload.
     *
     * @return the stream chunk
     */
    @JsonProperty("chunk")
    public StreamChunk chunk() {
        return getPayload();
    }

    /**
     * Gets the artifacts included with this event.
     *
     * @return artifacts map, or null if none
     */
    @JsonProperty("artifacts")
    public Map<String, Artifact> getArtifacts() {
        return artifacts;
    }

    @Override
    public MsgType msgType() {
        return getPayload().isLast() ? MsgType.COMPLETING : MsgType.PROGRESSING;
    }

    @Override
    public String title() {
        StreamChunk chunk = getPayload();
        if (chunk.isLast()) {
            return "Streaming Complete";
        }
        return "Streaming Content";
    }

    @Override
    public JobState jobState() {
        return snapshot().getState();
    }

    @Override
    @JsonProperty("content")
    public String getHumanMessage() {
        StreamChunk chunk = getPayload();
        if (chunk.content() != null) {
            return chunk.content();
        }
        return chunk.isLast() ? "Stream completed" : "Streaming...";
    }

    private String generateMessage(StreamChunk chunk) {
        if (chunk.isLast()) {
            return "Streaming complete";
        }
        return "Streaming chunk: " + (chunk.content() != null ?
            chunk.content().substring(0, Math.min(50, chunk.content().length())) : "");
    }
}