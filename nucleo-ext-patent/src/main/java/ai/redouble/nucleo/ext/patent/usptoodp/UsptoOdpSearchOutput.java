/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.usptoodp;

import ai.redouble.nucleo.ext.patent.artifacts.*;

import java.util.*;

/**
 * Output from USPTO Open Data Portal patent search.
 * Contains matching patents and total count.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class UsptoOdpSearchOutput {
    private List<PatentArtifact> patents;
    private Integer totalCount;
    public UsptoOdpSearchOutput() {
    }
    public List<PatentArtifact> getPatents() {
        return patents;
    }
    public void setPatents(List<PatentArtifact> patents) {
        this.patents = patents;
    }
    public Integer getTotalCount() {
        return totalCount;
    }
    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }
}
