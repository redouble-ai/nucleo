/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.ext.patent.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;

/**
 * Output from EPO bibliographic data retrieval.
 * Contains a fully populated PatentArtifact.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
public class EPOBiblioOutput  {
    @LLMDescription("Patent artifact with bibliographic data (title, abstract, applicants, inventors, dates, classifications)")
    private PatentArtifact patent;
    public EPOBiblioOutput() {
    }
    public PatentArtifact getPatent() {
        return patent;
    }
    public void setPatent(PatentArtifact patent) {
        this.patent = patent;
    }
}
