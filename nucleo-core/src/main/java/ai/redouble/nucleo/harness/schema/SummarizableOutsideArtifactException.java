/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.artifacts.*;

import java.util.*;

/**
 * Thrown when a field carries {@link LLMSummarizable} and the class that declares it is
 * not an {@link Artifact}. A summary stands in for a value a model can still read whole,
 * and only an artifact has the reference that value is read by; anywhere else the summary
 * would be all a model ever gets.
 *
 * @author Andrey Santrosyan
 * @since 0.1.6 (2026-11-24)
 */
public class SummarizableOutsideArtifactException extends RuntimeException {
    private final List<String> fields;

    /**
     * @param fields the misplaced fields, each as {@code declaring.Class.fieldName}
     */
    public SummarizableOutsideArtifactException(List<String> fields) {
        super(formatMessage(fields));
        this.fields = List.copyOf(fields);
    }

    /** The misplaced fields, each as {@code declaring.Class.fieldName}. */
    public List<String> getFields() {
        return fields;
    }

    /**
     * The message every check of the rule reports, so that a compile error, a build failure
     * and a refused start read alike.
     *
     * @param fields the misplaced fields, each as {@code declaring.Class.fieldName}
     */
    public static String formatMessage(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("@LLMSummarizable is allowed only on a field declared in a class that implements Artifact:"
                + " make the class an artifact, or remove the annotation. Declared outside an artifact:\n");
        for (String field : fields) {
            sb.append("  - ").append(field).append("\n");
        }
        return sb.toString();
    }
}
