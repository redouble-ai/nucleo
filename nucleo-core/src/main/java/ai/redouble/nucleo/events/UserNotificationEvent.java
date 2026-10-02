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
 * An event for important notifications that require user attention.
 * Unlike progress events, these are discrete messages about significant
 * occurrences during job execution. The severity maps to a {@link MsgType}: an error is
 * {@code ERROR}, a success {@code COMPLETING}, a warning or an info {@code MESSAGE}. The four
 * factories read the job state off the snapshot and carry no structured details.
 *
 * <p>Examples of when to use UserNotificationEvent:</p>
 * <ul>
 *   <li>"Found potential duplicate records - review recommended"</li>
 *   <li>"External service is slow, this may take longer than usual"</li>
 *   <li>"Successfully validated all 500 records"</li>
 *   <li>"Skipping 3 files due to unsupported format"</li>
 * </ul>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-11)
 */
public final class UserNotificationEvent extends AbstractJobEvent implements NotificationEvent, HumanReadable {

    /**
     * Severity levels for notifications.
     */
    public enum Severity {
        INFO,      // General information
        SUCCESS,   // Successful completion of something
        WARNING,   // Something the user should know about
        ERROR      // Problem that needs attention
    }

    private final String title;
    private final String notification;
    private final Severity severity;
    private final JobState jobState;
    private final Object details; // Optional structured data

    /**
     * Creates a user notification event.
     *
     * @param snapshot the job snapshot
     * @param title short title for the notification
     * @param notification the full notification message
     * @param severity the severity level
     * @param jobState the current job lifecycle state
     * @param details optional structured details (can be null)
     */
    public UserNotificationEvent(JobSnapshot snapshot, String title,
                                 String notification, Severity severity,
                                 JobState jobState, Object details) {
        super(snapshot);
        this.title = title;
        this.notification = notification;
        this.severity = severity;
        this.jobState = jobState;
        this.details = details;
        setMessage(notification);
    }

    /**
     * Creates a user notification event without details.
     *
     * @param snapshot the job snapshot
     * @param title short title for the notification
     * @param notification the full notification message
     * @param severity the severity level
     * @param jobState the current job lifecycle state
     */
    public UserNotificationEvent(JobSnapshot snapshot, String title,
                                 String notification, Severity severity, JobState jobState) {
        this(snapshot, title, notification, severity, jobState, null);
    }

    /**
     * Convenience factory for info notifications.
     */
    public static UserNotificationEvent info(JobSnapshot snapshot, String title, String message) {
        return new UserNotificationEvent(snapshot, title, message, Severity.INFO, snapshot.getState());
    }

    /**
     * Convenience factory for success notifications.
     */
    public static UserNotificationEvent success(JobSnapshot snapshot, String title, String message) {
        return new UserNotificationEvent(snapshot, title, message, Severity.SUCCESS, snapshot.getState());
    }

    /**
     * Convenience factory for warning notifications.
     */
    public static UserNotificationEvent warning(JobSnapshot snapshot, String title, String message) {
        return new UserNotificationEvent(snapshot, title, message, Severity.WARNING, snapshot.getState());
    }

    /**
     * Convenience factory for error notifications.
     */
    public static UserNotificationEvent error(JobSnapshot snapshot, String title, String message) {
        return new UserNotificationEvent(snapshot, title, message, Severity.ERROR, snapshot.getState());
    }

    public MsgType msgType() {
        return switch (severity) {
            case ERROR -> MsgType.ERROR;
            case SUCCESS -> MsgType.COMPLETING;
            case WARNING, INFO -> MsgType.MESSAGE;
        };
    }

    public String title() {
        return title;
    }

    public JobState jobState() {
        return jobState;
    }

    @Override
    @JsonProperty("content")
    public String getHumanMessage() {
        return notification;
    }

    public Severity getSeverity() {
        return severity;
    }

    public Object getDetails() {
        return details;
    }
}