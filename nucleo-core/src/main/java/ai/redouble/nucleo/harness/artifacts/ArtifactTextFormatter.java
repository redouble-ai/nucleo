/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;



/**
 * Deterministic text formatter for one artifact type: the same artifact content
 * always produces the same text. Formatters are pure functions - no timestamps,
 * no randomness, no state - so a system response assembled from formatted
 * artifacts is byte-identical for identical data.
 *
 * <p>Formatters live in {@link TextFormatterRegistry}, keyed by artifact class and
 * resolved up the class hierarchy, never on the artifact classes themselves
 * (artifacts are data objects; formatting is presentation).
 *
 * @param <T> the artifact type this formatter renders
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-12)
 */
public interface ArtifactTextFormatter<T extends Artifact> {

    /**
     * Renders the artifact as deterministic text.
     *
     * @param artifact the artifact to render
     * @return the canonical text form
     */
    String format(T artifact);
}
