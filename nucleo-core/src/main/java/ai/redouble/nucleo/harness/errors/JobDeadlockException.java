/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

/**
 * Exception thrown when a job attempts to block on another job's completion
 * while holding resources, which would cause deadlock.
 * <p>
 * This exception provides detailed diagnostic information to help developers
 * identify the exact location and nature of the deadlock in agentic workflows.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-07)
 */
public class JobDeadlockException extends IllegalStateException {
    private final String callerJobClass;
    private final String callerJobId;
    private final String callerWorkflowId;
    private final String calledJobClass;
    private final String calledJobId;
    private final String callLocation;

    public JobDeadlockException(
            String callerJobClass,
            String callerJobId,
            String callerWorkflowId,
            String calledJobClass,
            String calledJobId,
            String callLocation) {
        super(buildMessage(callerJobClass, callerJobId, callerWorkflowId,
            calledJobClass, calledJobId, callLocation));
        this.callerJobClass = callerJobClass;
        this.callerJobId = callerJobId;
        this.callerWorkflowId = callerWorkflowId;
        this.calledJobClass = calledJobClass;
        this.calledJobId = calledJobId;
        this.callLocation = callLocation;
    }

    private static String buildMessage(
            String callerJobClass,
            String callerJobId,
            String callerWorkflowId,
            String calledJobClass,
            String calledJobId,
            String callLocation) {
        StringBuilder msg = new StringBuilder();
        msg.append("DEADLOCK PREVENTION: Cannot call handle.get() from within job execution\n\n");
        msg.append("CALLER JOB:\n");
        msg.append("  Class: ").append(callerJobClass).append("\n");
        msg.append("  Job ID: ").append(callerJobId).append("\n");
        msg.append("  Workflow ID: ").append(callerWorkflowId).append("\n");
        msg.append("\nCALLED JOB:\n");
        msg.append("  Class: ").append(calledJobClass).append("\n");
        msg.append("  Job ID: ").append(calledJobId).append("\n");
        if (callLocation != null && !callLocation.isEmpty()) {
            msg.append("\nCALL LOCATION:\n");
            msg.append("  ").append(callLocation).append("\n");
        }
        msg.append("\nPROBLEM:\n");
        msg.append("  Blocking on child job completion while holding resources causes deadlock.\n");
        msg.append("\nSOLUTION:\n");
        msg.append("  Use dependency pattern instead:\n");
        msg.append("    JobHandle<X> child = dispatcher.submit(userId, childJob);\n");
        msg.append("    JobHandle<Y> parent = dispatcher.submit(userId, parentJob, Set.of(child));\n");
        msg.append("  The parent job will only execute after child completes.\n");
        return msg.toString();
    }

    public String getCallerJobClass() {
        return callerJobClass;
    }

    public String getCallerJobId() {
        return callerJobId;
    }

    public String getCallerWorkflowId() {
        return callerWorkflowId;
    }

    public String getCalledJobClass() {
        return calledJobClass;
    }

    public String getCalledJobId() {
        return calledJobId;
    }

    public String getCallLocation() {
        return callLocation;
    }
}
