/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

/**
 * How a file's text was, or was not, obtained. The three that cost something are the point
 * of the demo: most files never touch a model, a scan goes to a model that can see, and
 * only what the code cannot place goes to a model that can judge.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public enum Tier {
    /** Read by code: plain text, source, data files, PDF with a text layer, Office documents. */
    DETERMINISTIC,
    /** Transcribed by a vision-capable model: images, and PDFs with no text layer. */
    VISION,
    /** Judged by a small model from its first bytes, then read by code when it turned out to be text. */
    CLASSIFIER,
    /** Binary with nothing to extract, left out with the reason. */
    SKIPPED,
    /** Something no tier could place; a person looks. */
    NEEDS_PERSON,
    /** Refused at admission: the run's spend cap. */
    REFUSED,
    /** The job failed; the reason is on the row. */
    FAILED
}
