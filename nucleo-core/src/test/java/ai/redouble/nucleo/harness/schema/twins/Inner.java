/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema.twins;

/**
 * A second class named {@code Inner}, so a schema can be asked to hold two types of one simple
 * name and refuse.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
public class Inner {
    private String label;

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}
