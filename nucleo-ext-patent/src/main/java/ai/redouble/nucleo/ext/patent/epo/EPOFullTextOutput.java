/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.ext.patent.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;

/**
 * Output from EPOFullTextTool.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class EPOFullTextOutput {
    @LLMDescription("Patent artifact with full bibliographic data, claims, and description")
    private PatentFullContentArtifact patent;
    public EPOFullTextOutput() {
    }
    public PatentFullContentArtifact getPatent() {
        return patent;
    }
    public void setPatent(PatentFullContentArtifact patent) {
        this.patent = patent;
    }
}
