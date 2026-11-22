/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.annotations;

import com.fasterxml.jackson.databind.*;

/**
 * What a deployment stores for one annotation: the text of its {@code value} and, apart from
 * it, any of its other attributes. Whatever is left null comes from the code.
 *
 * <p>The two are kept apart so that the text a person reads and edits is plain text.
 * {@code value} is the annotation's {@code value} attribute; for an annotation whose
 * {@code value} is a list of strings it is one entry per line. {@code attributes} is a JSON
 * object keyed by attribute name, for example {@code {"readOnly": true}}, and never holds
 * {@code value}.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-22)
 */
public class AnnotationOverride {
    private String value;
    private JsonNode attributes;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public JsonNode getAttributes() {
        return attributes;
    }

    public void setAttributes(JsonNode attributes) {
        this.attributes = attributes;
    }
}
