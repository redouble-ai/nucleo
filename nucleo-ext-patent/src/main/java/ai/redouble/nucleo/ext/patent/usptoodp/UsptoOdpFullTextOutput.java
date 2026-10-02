/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.usptoodp;

import ai.redouble.nucleo.ext.patent.artifacts.*;

/**
 * Output from fetching full patent text from USPTO Open Data Portal.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class UsptoOdpFullTextOutput {
    private PatentFullContentArtifact patent;
    public UsptoOdpFullTextOutput() {
    }
    public PatentFullContentArtifact getPatent() {
        return patent;
    }
    public void setPatent(PatentFullContentArtifact patent) {
        this.patent = patent;
    }
}
