/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import ai.redouble.nucleo.harness.schema.*;

/**
 * The classifier's verdict on a file nothing else could place.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class Classification {
    public enum Kind {
        /** Readable text under an extension the code did not know: read it as text. */
        TEXT,
        /** A binary with no text to extract: skip it. */
        BINARY,
        /** Neither, or not decidable from the head: a person looks. */
        NEEDS_PERSON
    }

    @LLMRequired
    @LLMDescription("TEXT when the head is readable text in any language or format (source, config, data, prose) whatever the extension; BINARY when it is a compiled, compressed, encrypted or media file with no text to extract; NEEDS_PERSON when you cannot tell from the head")
    private Kind kind;
    @LLMRequired
    @LLMDescription("One sentence: what the file appears to be and what told you")
    private String reason;

    public Kind getKind() {return kind;}

    public void setKind(Kind kind) {this.kind = kind;}

    public String getReason() {return reason;}

    public void setReason(String reason) {this.reason = reason;}
}
