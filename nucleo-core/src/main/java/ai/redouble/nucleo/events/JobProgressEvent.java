/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.events;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import com.fasterxml.jackson.annotation.*;

/**
 * Job progress event with typed payload. Implements {@link HumanReadable} for
 * direct delivery to WebSocket observers.
 *
 * <p>Automatically maps progress percentage to lifecycle {@link MsgType}:
 * 0% is STARTING, 1-99% PROGRESSING, 100% COMPLETING, no percent STATUS_UPDATE. The title is
 * the job's display name and the job state is the snapshot's.
 *
 * <p>The {@code getPayload()} method is {@code @JsonIgnore} to prevent generic serialization;
 * subclasses expose payloads with specific names (e.g., {@link ContentStreamEvent#chunk()}).
 * The payload's {@code toString()} is used as the human-readable message content.
 *
 * @param <P> Payload type
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-15)
 */
public non-sealed class JobProgressEvent<P> extends AbstractJobEvent implements ProgressEvent, HumanReadable {

    private P payload;
    private Integer progressPercent;  // null if not a progress update

    /**
     * Creates a new update event with just data.
     */
    public JobProgressEvent(JobSnapshot snapshot, P payload) {
        super(snapshot);
        this.payload = payload;
        setMessage(payload != null ? payload.toString() : null);
    }

    /**
     * Creates a new update event with data and progress.
     */
    public JobProgressEvent(JobSnapshot snapshot, P payload, int progressPercent) {
        super(snapshot);
        this.payload = payload;
        this.progressPercent = progressPercent;
        setMessage(payload != null ? payload.toString() : null);
    }

    public MsgType msgType() {
        if (progressPercent == null) return MsgType.STATUS_UPDATE;
        if (progressPercent == 0) return MsgType.STARTING;
        if (progressPercent >= 100) return MsgType.COMPLETING;
        return MsgType.PROGRESSING;
    }

    public String title() {
        return snapshot() != null ? snapshot().getDisplayName() : null;
    }

    public JobState jobState() {
        return snapshot() != null ? snapshot().getState() : null;
    }

    // Getters and setters

    @JsonIgnore
    public P getPayload() {
        return payload;
    }

    /** For the subclasses' constructors: an event is immutable once built. */
    protected void setPayload(P payload) {
        this.payload = payload;
    }

    public Integer getProgressPercent() {
        return progressPercent;
    }

    /** For the subclasses' constructors: an event is immutable once built. */
    protected void setProgressPercent(Integer progressPercent) {
        this.progressPercent = progressPercent;
    }

    /**
     * Helper to check if this event has progress information.
     */
    public boolean hasProgress() {
        return progressPercent != null && progressPercent >= 0;
    }

    @Override
    @JsonProperty("content")
    public String getHumanMessage() {
        return message();
    }
}