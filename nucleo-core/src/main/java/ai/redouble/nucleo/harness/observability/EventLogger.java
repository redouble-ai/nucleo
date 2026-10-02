/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.events.heartbeat.*;
import ai.redouble.nucleo.events.retry.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import org.slf4j.*;
import org.slf4j.event.Level;

import java.util.function.*;

/**
 * Console log of every job event but the operational ones, one colored line per event on the
 * logger of the class the event is about: the workflow as a colored prefix (shortened to its
 * first ten and last four characters past fifteen), the job id and state in brackets, then
 * the event's message. An event with no job snapshot is a system event and reads
 * {@code [SYSTEM] message}. A failure whose error is neither LLM-readable nor a wrapped child
 * failure is followed by its stack trace at ERROR; an LLM-readable failure is part of the
 * normal tool flow and gets no trace. {@link OperationalEvent}s are excluded by the
 * predicate, since {@link SystemHealthReporter} renders them.
 *
 * <p>The logger is the job's own class plus one segment naming the kind of event, and each
 * kind has a fixed level:</p>
 * <ul>
 *   <li>{@code failure} - a {@link FailureEvent}, WARN</li>
 *   <li>{@code lifecycle} - any other {@link LifecycleEvent}, INFO</li>
 *   <li>{@code retry} - a {@link RetryEvent}, DEBUG</li>
 *   <li>{@code workflow} - a {@link WorkflowTerminationEvent}, INFO</li>
 *   <li>{@code notification} - a {@link UserNotificationEvent}, at its severity: INFO and
 *       SUCCESS at DEBUG, WARNING at WARN, ERROR at ERROR</li>
 *   <li>{@code stream} - a {@link ContentStreamEvent}, DEBUG</li>
 *   <li>{@code progress} - any other {@link ProgressEvent}, DEBUG</li>
 *   <li>{@code system} - a {@link SystemEvent}, INFO</li>
 *   <li>{@code heartbeat} - a {@link HeartbeatEvent}, INFO</li>
 *   <li>{@code event} - any other event, INFO</li>
 * </ul>
 * <p>So {@code ai.redouble.demo.DemoAgent.progress} carries that agent's progress, a layout
 * that prints the logger names the job and the kind of line, and a deployment sets levels the
 * way it does for any other logger: the job's class for all of its events, the class plus a
 * kind for one kind, a package for every job under it. A system event, which has no job, logs
 * under the event's class plus its kind. This observer's own faults log under this class.</p>
 *
 * <p>While an event's lines are written, the MDC holds its workflow id under
 * {@link #MDC_WORKFLOW_ID}, so a backend can lower the threshold of one workflow alone - in
 * Logback, a {@code DynamicThresholdFilter} keyed on it.</p>
 *
 * <p>All EventLogger instances are functionally identical (stateless) and will be
 * deduplicated by the MessageBus. Only one EventLogger can be registered per bus.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-15)
 */
public class EventLogger implements JobObserver<JobEvent> {

    /** The MDC key holding the workflow id while an event's lines are written. */
    public static final String MDC_WORKFLOW_ID = "workflowId";

    // Only this observer's own faults. Every event line goes to loggerFor(event, category).
    private static final Logger log = LoggerFactory.getLogger(EventLogger.class);

    // ANSI color codes for states
    private static final String RESET = "\u001B[0m";
    private static final String LIME_GREEN = "\u001B[92m";  // Bright green for success
    private static final String LIGHT_BLUE = "\u001B[94m";   // Bright blue for running/in-progress
    private static final String MAGENTA = "\u001B[95m";      // Bright magenta for failure
    private static final String YELLOW = "\u001B[93m";       // Bright yellow for warnings
    private static final String CYAN = "\u001B[96m";         // Bright cyan for queued/scheduled
    private static final String GRAY = "\u001B[90m";         // Gray for cancelled/timed out

    // 40 distinct 256-color palette colors for job message bodies.
    // Deliberately excludes basic ANSI colors (31-36m, 91-96m, 90m) to avoid collisions
    // with log-level colors (red=error, green=debug, yellow=warn) and state colors above.
    private static final String[] JOB_COLORS = {
            // Oranges / warm
            "\u001B[38;5;208m", // Orange
            "\u001B[38;5;214m", // Gold
            "\u001B[38;5;202m", // Dark Orange
            "\u001B[38;5;172m", // Burnt Orange
            "\u001B[38;5;173m", // Copper
            "\u001B[38;5;179m", // Sand
            "\u001B[38;5;215m", // Peach
            "\u001B[38;5;220m", // Gold Yellow
            "\u001B[38;5;178m", // Dark Gold
            // Purples / violets
            "\u001B[38;5;165m", // Purple
            "\u001B[38;5;129m", // Violet
            "\u001B[38;5;99m",  // Light Purple
            "\u001B[38;5;105m", // Light Violet
            "\u001B[38;5;133m", // Mauve
            "\u001B[38;5;134m", // Orchid
            "\u001B[38;5;140m", // Medium Purple
            "\u001B[38;5;141m", // Lavender
            "\u001B[38;5;176m", // Light Orchid
            "\u001B[38;5;177m", // Thistle
            // Blues (distinct from system blue / bright blue)
            "\u001B[38;5;117m", // Light Sky Blue
            "\u001B[38;5;68m",  // Steel Blue
            "\u001B[38;5;69m",  // Cornflower
            "\u001B[38;5;74m",  // Cadet Blue
            "\u001B[38;5;75m",  // Medium Sky Blue
            "\u001B[38;5;110m", // Light Steel Blue
            "\u001B[38;5;111m", // Periwinkle
            // Aqua / teal (distinct from system cyan)
            "\u001B[38;5;87m",  // Light Cyan
            "\u001B[38;5;123m", // Aqua
            "\u001B[38;5;80m",  // Medium Aqua
            "\u001B[38;5;116m", // Pale Turquoise
            // Greens (distinct from system green)
            "\u001B[38;5;156m", // Light Green
            "\u001B[38;5;118m", // Chartreuse
            "\u001B[38;5;154m", // Yellow Green
            "\u001B[38;5;149m", // Olive
            "\u001B[38;5;150m", // Moss
            // Pinks / reds (distinct from system red)
            "\u001B[38;5;167m", // Indian Red
            "\u001B[38;5;174m", // Light Pink
            "\u001B[38;5;211m", // Pink
            "\u001B[38;5;219m", // Plum
            "\u001B[38;5;183m"  // Light Plum
    };

    // Distinct colors for workflow identification (using background colors for visibility)
    private static final String[] WORKFLOW_COLORS = {
            "\u001B[48;5;22m",  // Dark green background
            "\u001B[48;5;23m",  // Dark teal background
            "\u001B[48;5;24m",  // Dark blue background
            "\u001B[48;5;52m",  // Dark red background
            "\u001B[48;5;53m",  // Dark purple background
            "\u001B[48;5;54m",  // Dark magenta background
            "\u001B[48;5;58m",  // Dark gray background
            "\u001B[48;5;88m",  // Dark crimson background
            "\u001B[48;5;89m",  // Dark plum background
            "\u001B[48;5;94m",  // Brown background
            "\u001B[48;5;95m",  // Light brown background
            "\u001B[48;5;130m", // Dark orange background
            "\u001B[48;5;136m", // Dark gold background
            "\u001B[48;5;137m", // Tan background
            "\u001B[48;5;17m",  // Navy blue background
    };

    @Override
    public Predicate<JobEvent> getPredicate() {
        // Rate limiter events (OperationalEvent category) are handled by
        // SystemHealthReporter, which has its own sampling and formatting.
        // Filtering here avoids double-printing and keeps the console log
        // focused on lifecycle / progress / retry / notification events.
        return event -> !(event instanceof OperationalEvent);
    }

    @Override
    public void observe(JobEvent event) {
        try {
            Category category = categoryOf(event);
            Logger origin = loggerFor(event, category);
            JobSnapshot snapshot = event.snapshot();
            // The workflow id rides the MDC for this event's lines, so a logging backend can
            // lower the threshold of one workflow (Logback's DynamicThresholdFilter) while every
            // other workflow stays at the configured levels
            try (MDC.MDCCloseable _ = snapshot != null && snapshot.getWorkflowId() != null
                    ? MDC.putCloseable(MDC_WORKFLOW_ID, snapshot.getWorkflowId())
                    : null) {
                if (origin.isEnabledForLevel(category.level())) {
                    // Most logging backends strip the ANSI codes when writing to files
                    emit(origin, category.level(), formatEventWithColor(event));
                }
                // Print full exception for failures, but skip:
                //   - wrapped child failures (the child's FailureEvent already printed its trace)
                //   - LLM-readable exceptions (they're part of normal tool flow; the message
                //     above already captures the meaningful part, the trace is noise)
                if (event instanceof FailureEvent failure) {
                    Throwable error = failure.getError();
                    if (error != null && !isWrappedChildFailure(error) && !(error instanceof LLMReadable)) {
                        origin.error(error.getMessage(), error);
                    }
                }
            }
        }
        catch (Exception e) {
            log.error("[EventLogger] Error observing event", e);
        }
    }

    /**
     * What an event is, as its logger's last name segment, and the level its line is written
     * at. The failure branch comes before the lifecycle one because a failure is also a
     * terminal lifecycle event, and the stream branch before the progress one because a
     * stream chunk is also a progress event.
     */
    private static Category categoryOf(JobEvent event) {
        return switch (event) {
            case FailureEvent _ -> new Category("failure", Level.WARN);
            case LifecycleEvent _ -> new Category("lifecycle", Level.INFO);
            case RetryEvent _ -> new Category("retry", Level.DEBUG);
            case WorkflowTerminationEvent _ -> new Category("workflow", Level.INFO);
            case UserNotificationEvent notification -> new Category("notification", switch (notification.getSeverity()) {
                // a running job's narration - its reasoning, the tool it starts, the tool that
                // returned - is detail; a warning or an error is not
                case INFO, SUCCESS -> Level.DEBUG;
                case WARNING -> Level.WARN;
                case ERROR -> Level.ERROR;
            });
            case ContentStreamEvent _ -> new Category("stream", Level.DEBUG);
            case ProgressEvent _ -> new Category("progress", Level.DEBUG);
            case SystemEvent _ -> new Category("system", Level.INFO);
            case HeartbeatEvent _ -> new Category("heartbeat", Level.INFO);
            default -> new Category("event", Level.INFO);
        };
    }

    /**
     * The logger of the class the line is about, one segment deeper for the category: the
     * job's own class, so the line is attributed where it happened, and the category, so a
     * deployment sets the level of one kind of event on one job, one package or everything
     * under a prefix, and setting the job's class alone covers all of its events. A system
     * event has no snapshot, and a snapshot that came back from JSON has no {@code jobClass} -
     * both log under the event's own class, which is what is left of the origin. The backing
     * framework caches loggers by name, so this is a map lookup per event.
     */
    private static Logger loggerFor(JobEvent event, Category category) {
        JobSnapshot snapshot = event.snapshot();
        Class<?> origin = snapshot != null && snapshot.jobClass() != null ? snapshot.jobClass() : event.getClass();
        return LoggerFactory.getLogger(origin.getName() + "." + category.name());
    }

    private static void emit(Logger logger, Level level, String line) {
        switch (level) {
            case ERROR -> logger.error(line);
            case WARN -> logger.warn(line);
            case INFO -> logger.info(line);
            case DEBUG -> logger.debug(line);
            case TRACE -> logger.trace(line);
        }
    }

    /**
     * The logger name segment and the level of one kind of event.
     *
     * @param name  the last segment of the logger name
     * @param level the level the event's line is written at
     */
    private record Category(String name, Level level) {
    }

    /**
     * Format event with ANSI color codes.
     * Workflow gets consistent background color, state gets state-specific color, message gets job-specific color.
     */
    private String formatEventWithColor(JobEvent event) {
        // Check if this is a system event (no descriptor)
        if (event.snapshot() == null) {
            // System-level event (SchedulerStarted, SchedulerStopping, etc.)
            String message = event.message();
            // Use cyan for system events
            return CYAN + "[SYSTEM] " + RESET + message;
        }
        // Get the core fields for job events
        String jobId = event.snapshot().getJobId();
        String workflowId = event.snapshot().getWorkflowId();
        JobState state = event.snapshot().getState();
        String message = event.message();

        // Choose workflow color based on workflow ID hash
        String workflowColor = WORKFLOW_COLORS[Math.abs(workflowId.hashCode()) % WORKFLOW_COLORS.length];
        // Format workflow prefix: first 10 chars + ... + last 4 chars
        String workflowPrefix = workflowId.length() > 15
                ? workflowId.substring(0, 10) + "..." + workflowId.substring(workflowId.length() - 4)
                : workflowId;

        // Choose state color
        String stateColor = null;
        if (state != null) {
            stateColor = switch (state) {
                case COMPLETED -> LIME_GREEN;
                case RUNNING -> LIGHT_BLUE;
                case IDLE, QUEUED, SCHEDULED -> CYAN;
                case FAILED, TIMED_OUT -> MAGENTA;
                case CANCELLED -> GRAY;
                case CANCELLING -> YELLOW;
            };
        }

        // Choose job color based on job ID hash
        // Top-level job (parentJobId == workflowId) uses the workflow background color for visual continuity.
        // The Workflow identity is never submitted as a job; the first real job has parentJobId == workflowId.
        String parentJobId = event.snapshot().getParentJobId();
        String jobColor = null;
        if (jobId != null && !jobId.equals("scheduler")) {
            if (workflowId.equals(parentJobId)) {
                jobColor = workflowColor;
            }
            else {
                int colorIndex = Math.abs(jobId.hashCode()) % JOB_COLORS.length;
                jobColor = JOB_COLORS[colorIndex];
            }
        }

        // Build the formatted message
        StringBuilder result = new StringBuilder();

        // Add workflow identifier with consistent background color
        result.append(workflowColor);
        result.append(" ");
        result.append(workflowPrefix);
        result.append(" ");
        result.append(RESET);
        result.append(" ");

        // Format the prefix with job color for ID and state color for state
        if (jobId != null) {
            result.append("[");
            if (jobColor != null) {
                result.append(jobColor);
            }
            result.append(jobId);
            result.append(RESET);
            result.append("/");
            if (stateColor != null) {
                result.append(stateColor);
            }
            result.append(state);
            result.append(RESET);
            result.append("]");
        }
        else {
            result.append("[Scheduler/");
            if (stateColor != null) {
                result.append(stateColor);
            }
            result.append(state != null ? state : "INFO");
            result.append(RESET);
            result.append("]");
        }

        // Add message with job color
        if (message != null && !message.isEmpty()) {
            result.append(" ");
            if (jobColor != null) {
                result.append(jobColor);
            }
            result.append(message);
            if (jobColor != null) {
                result.append(RESET);
            }
        }

        return result.toString();
    }

    /**
     * Returns true if this exception is wrapping a child job's failure.
     * In that case, skip printing the stack trace since the child's FailureEvent
     * already printed it.
     */
    private boolean isWrappedChildFailure(Throwable error) {
        return error instanceof java.util.concurrent.ExecutionException && error.getCause() != null;
    }

    /**
     * All EventLogger instances are functionally identical (stateless).
     * This ensures only one EventLogger can be registered per message bus.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        return obj != null && getClass() == obj.getClass();
    }

    /**
     * All EventLogger instances share the same hash code since they're functionally identical.
     */
    @Override
    public int hashCode() {
        return EventLogger.class.hashCode();
    }
}