/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import java.lang.annotation.*;

/**
 * Annotation to provide a human-readable display name and action verb for jobs and tools.
 * This name and action are shown to users in UI, logs, and event messages.
 *
 * <p>Examples:
 * <pre>
 * {@code @DisplayName(value = "PubMed Fetch", action = "Fetching Complete PubMed Articles")}
 * public class PubMedFetchTool extends AbstractTool {...}
 *
 * {@code @DisplayName("Document Ingest Pipeline")}  // action optional
 * public class DocumentIngestJob extends AbstractJob {...}
 * </pre>
 *
 * <p>{@link JobSnapshot} reads it: a job's own {@link Job#getDisplayName()} wins when it
 * answers, then this annotation, then the class simple name; the action is this annotation's
 * or the empty string.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-15)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DisplayName {
    /**
     * The human-readable display name for this job or tool.
     * Should be clear and concise (e.g., "Search PubMed", "AI Research Assistant").
     *
     * @return the display name
     */
    String value();
    /**
     * Optional action verb describing what this job/tool is doing.
     * Examples: "Searching", "Thinking", "Processing", "Analyzing".
     * When not provided the snapshot's action is the empty string; nothing is inferred.
     *
     * @return the action verb, or empty string if not specified
     */
    String action() default "";
}
