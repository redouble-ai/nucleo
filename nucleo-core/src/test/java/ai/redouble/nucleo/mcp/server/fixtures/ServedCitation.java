/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server.fixtures;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;

/**
 * An artifact a served tool answers with. Its {@code @TypeAlias} rides the ref that
 * serialization emits, which is what lets a redouble consumer rebuild this type.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-04)
 */
@TypeAlias("link:cite:served-fixture")
public class ServedCitation extends LinkArtifact {
    @LLMDescription("Digital object identifier - one wrong digit points at nothing")
    private String doi;

    public String getDoi() {
        return doi;
    }

    public void setDoi(String doi) {
        this.doi = doi;
    }
}
