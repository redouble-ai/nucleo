/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.llm.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;

/**
 * Workflow observer that logs streaming events with formatted console output.
 *
 * <p>Displays LLM streaming content with visual boxes for reasoning and tool execution.
 * Tracks progress updates and handles both streaming chunks and status messages.
 *
 * <p>Observer instances are unique per workflow+configuration, allowing multiple observers
 * with different verbosity settings for the same workflow while preventing exact duplicates.
 *
 * @param <T> the type of JobEvent to observe
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-10)
 */
public class StreamingLogObserver<T extends JobEvent> extends AbstractWorkflowObserver<T> {
    private static final Logger log = LoggerFactory.getLogger(StreamingLogObserver.class);
    /** Width of a boxed message, borders included. */
    private static final int BOX_WIDTH = 68;

    private final boolean logChunks;
    private final boolean logStatus;

    /**
     * Creates a streaming log observer for a specific workflow with default settings.
     * Logs both chunks and status updates, with no inactivity timeout.
     *
     * @param workflowId the workflow ID to observe
     */
    public StreamingLogObserver(String workflowId) {
        this(workflowId, null, true, true);
    }

    /**
     * Creates a streaming log observer with inactivity timeout.
     *
     * @param workflowId the workflow ID to observe
     * @param inactivityTimeout duration after which the observer is considered stale (null for no timeout)
     */
    public StreamingLogObserver(String workflowId, Duration inactivityTimeout) {
        this(workflowId, inactivityTimeout, true, true);
    }

    /**
     * Creates a streaming log observer with full configuration.
     *
     * @param workflowId the workflow ID to observe
     * @param inactivityTimeout duration after which the observer is considered stale (null for no timeout)
     * @param logChunks whether to log streaming content chunks
     * @param logStatus whether to log status and progress updates
     */
    public StreamingLogObserver(String workflowId, Duration inactivityTimeout, boolean logChunks, boolean logStatus) {
        super(workflowId, inactivityTimeout);
        this.logChunks = logChunks;
        this.logStatus = logStatus;
    }

    @Override
    protected void handleEvent(T event) {
        // Only progress events carry chunks, progress and status lines; a content stream event is one of them
        if (!(event instanceof JobProgressEvent<?> update)) {
            return;
        }
        Object payload = update.getPayload();

        // Handle streaming chunks
        if (payload instanceof StreamChunk chunk && logChunks) {
            logChunk(chunk);
        }

        // Handle progress updates
        if (update.hasProgress() && logStatus) {
            String message = "Progress: " + update.getProgressPercent() + "%";
            if (payload != null && !(payload instanceof StreamChunk)) {
                message += " - " + payload;
            }
            log.info(message);
        }

        // Handle plain string messages without progress
        if (payload instanceof String && !update.hasProgress() && logStatus) {
            log.info((String)payload);
        }
    }

    /**
     * Logs a streaming content chunk with appropriate formatting.
     */
    private void logChunk(StreamChunk chunk) {
        if (chunk.isLast()) {
            System.out.println(); // New line after streaming
            log.info("════════════════════ STREAMING COMPLETE ════════════════════");
        }
        else {
            String content = chunk.content();

            // Make reasoning and tool messages stand out with special formatting
            if (content.startsWith("[REASONING")) {
                printBoxedMessage("╔", "║", "╚", "══", "🧠 THINKER", content.trim());
            }
            else if (content.startsWith("[TOOLS TO EXECUTE")) {
                printBoxedMessage("┌", "│", "└", "──", "🔧", content.trim());
            }
            else if (content.startsWith("[TOOL COMPLETED")) {
                System.out.println("    ✅ " + content.trim());
            }
            else {
                // Regular streaming content - print inline
                System.out.print(chunk.content());
                System.out.flush();
            }
        }
    }

    /**
     * Prints a message in a box with proper text wrapping.
     *
     * @param topChar    character for top border
     * @param sideChar   character for side borders
     * @param bottomChar character for bottom border
     * @param lineChar   character for horizontal lines
     * @param icon       icon to display
     * @param content    content to display
     */
    private void printBoxedMessage(String topChar, String sideChar, String bottomChar, String lineChar, String icon, String content) {
        System.out.println();

        // Calculate available width for text (subtract borders and icon)
        int maxWidth = BOX_WIDTH;
        int textWidth = maxWidth - 4 - icon.length() - 1; // 4 for borders and spaces, 1 for space after icon

        // Print top border
        System.out.print(topChar);
        for (int i = 0; i < maxWidth - 2; i++) {
            System.out.print(lineChar);
        }
        System.out.println(topChar.equals("╔") ? "╗" : "┐");

        // Wrap and print content
        String fullContent = icon + " " + content;
        int start = 0;
        boolean firstLine = true;

        while (start < fullContent.length()) {
            int end = Math.min(start + (firstLine ? maxWidth - 4 : textWidth), fullContent.length());

            // Try to break at a word boundary if we're not at the end
            if (end < fullContent.length() && end > start + 10) {
                int lastSpace = fullContent.lastIndexOf(' ', end);
                if (lastSpace > start && lastSpace > end - 20) {
                    end = lastSpace;
                }
            }

            String line = fullContent.substring(start, end).trim();

            // Print the line with proper padding
            System.out.print(sideChar + " ");
            if (!firstLine && !line.isEmpty()) {
                // Indent continuation lines
                System.out.print("  ");
                System.out.print(line);
                int padding = maxWidth - 4 - 2 - line.length();
                for (int i = 0; i < padding; i++) {
                    System.out.print(" ");
                }
            }
            else {
                System.out.print(line);
                int padding = maxWidth - 4 - line.length();
                for (int i = 0; i < padding; i++) {
                    System.out.print(" ");
                }
            }
            System.out.println(" " + sideChar);

            firstLine = false;
            start = end;

            // Skip leading spaces on next line
            while (start < fullContent.length() && fullContent.charAt(start) == ' ') {
                start++;
            }
        }

        // Print bottom border
        System.out.print(bottomChar);
        for (int i = 0; i < maxWidth - 2; i++) {
            System.out.print(lineChar);
        }
        System.out.println(bottomChar.equals("╚") ? "╝" : "┘");
    }

    @Override
    protected void onCleanup() {
        // Log that streaming observer is being cleaned up
        log.info("StreamingLogObserver for workflow {} is being cleaned up", workflowId);
    }

    /**
     * Two StreamingLogObserver instances are equal if they observe the same workflow with
     * the same two logging flags and both have, or both lack, an inactivity timeout; the
     * timeout's length does not count. This prevents duplicate observers with identical
     * behavior, while allowing different logging configurations for the same workflow.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof StreamingLogObserver)) return false;
        StreamingLogObserver<?> that = (StreamingLogObserver<?>) obj;
        return logChunks == that.logChunks &&
               logStatus == that.logStatus &&
               Objects.equals(workflowId, that.workflowId) &&
               hasInactivityTimeout() == that.hasInactivityTimeout();
    }

    /**
     * Hash code based on the workflow ID, the two logging flags and whether a timeout exists.
     */
    @Override
    public int hashCode() {
        return Objects.hash(workflowId, logChunks, logStatus, hasInactivityTimeout());
    }
}