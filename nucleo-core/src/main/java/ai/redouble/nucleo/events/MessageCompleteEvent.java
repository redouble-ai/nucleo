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
 * Event signaling that a single message exchange in a chat session has completed.
 *
 * <p>Published by {@link ai.redouble.nucleo.tools.thinking.ReactiveThinker} after each message iteration,
 * guaranteeing the frontend always receives a terminal signal per message exchange.
 * The frontend uses this to re-enable input, stop typing indicators, and finalize
 * any pending artifacts.</p>
 *
 * <p>This is distinct from workflow-level events:</p>
 * <ul>
 *   <li>{@code MessageCompleteEvent} - one message exchange is done, conversation continues</li>
 *   <li>{@link WorkflowCompleteEvent} - the entire chat session or workflow has ended</li>
 * </ul>
 *
 * <p>Published AFTER the last {@link ContentStreamEvent} (if any) and AFTER conversation
 * persistence. Content streaming and lifecycle signaling are separate concerns.</p>
 *
 * <p>Implements {@link JobEvent} directly, outside every sealed category, so it reaches only
 * subscribers that ask for it by type or subscribe to everything. A success reads
 * "Message processed" as {@code COMPLETING} under the title "Message Complete"; a failure
 * "Message processing failed" as {@code FAILING} under "Message Failed", with the error
 * description it was given.</p>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-14)
 * @see WorkflowCompleteEvent
 * @see ContentStreamEvent
 */
public class MessageCompleteEvent extends AbstractJobEvent implements HumanReadable {
    private final boolean successful;
    private final String errorMessage;

    /**
     * Creates a message completion event with the given outcome and no error description.
     *
     * @param snapshot the job snapshot
     * @param successful whether the message was processed successfully
     */
    public MessageCompleteEvent(JobSnapshot snapshot, boolean successful) {
        this(snapshot, successful, null);
    }

    /**
     * Creates a message completion event with optional error.
     *
     * @param snapshot the job snapshot
     * @param successful whether the message was processed successfully
     * @param errorMessage error description if failed (null for success)
     */
    public MessageCompleteEvent(JobSnapshot snapshot, boolean successful, String errorMessage) {
        super(snapshot);
        this.successful = successful;
        this.errorMessage = errorMessage;
        setMessage(successful ? "Message processed" : "Message processing failed");
    }

    @JsonProperty("successful")
    public boolean isSuccessful() {
        return successful;
    }

    @JsonProperty("errorMessage")
    public String getErrorMessage() {
        return errorMessage;
    }

    public MsgType msgType() {
        return successful ? MsgType.COMPLETING : MsgType.FAILING;
    }

    public String title() {
        return successful ? "Message Complete" : "Message Failed";
    }

    public JobState jobState() {
        return snapshot().getState();
    }

    @Override
    @JsonProperty("content")
    public String getHumanMessage() {
        return message();
    }
}
