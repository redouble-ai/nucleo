/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit.models;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input for fetching full details of specific bioRxiv/medRxiv preprints by DOI.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-12)
 */
@LLMDescription("Parameters for fetching full preprint details from bioRxiv/medRxiv by DOI")
public class BioRxivFetchInput  {

    @LLMRequired
    @LLMDescription("Digital Object Identifier (DOI) of the preprint (e.g., '10.1101/2024.01.15.575432')")
    @LLMExample("10.1101/2024.01.15.575432")
    private String doi;

    @LLMDescription("Server where the preprint is hosted: 'biorxiv' or 'medrxiv' (auto-detected from DOI if not provided)")
    @LLMExample("biorxiv")
    private String server;

    public String getDoi() {
        return doi;
    }

    public void setDoi(String doi) {
        this.doi = doi;
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }
}
