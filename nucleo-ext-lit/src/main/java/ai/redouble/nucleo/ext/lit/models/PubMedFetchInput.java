/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit.models;

import ai.redouble.nucleo.harness.schema.*;
import java.util.*;

/**
 * Input for fetching full details of specific PubMed articles by PMID.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-03)
 */
@LLMDescription("Parameters for fetching full article details from PubMed by PMID")
public class PubMedFetchInput  {

    @LLMRequired
    @LLMDescription("List of PubMed IDs (PMIDs) to fetch. Maximum 100 per request.")
    @LLMExample("[\"12345678\", \"87654321\"]")
    private List<String> pmids;

    @LLMDescription("Whether to include full abstract text (default true)")
    private Boolean includeAbstract;

    @LLMDescription("Whether to include MeSH terms and keywords (default true)")
    private Boolean includeMeshTerms;

    @LLMDescription("Whether to include grant/funding information (default false)")
    private Boolean includeGrants;

    @LLMDescription("Whether to include full author affiliations (default false)")
    private Boolean includeAffiliations;

    public List<String> getPmids() {
        return pmids;
    }

    public void setPmids(List<String> pmids) {
        this.pmids = pmids;
    }

    public Boolean getIncludeAbstract() {
        return includeAbstract;
    }

    public void setIncludeAbstract(Boolean includeAbstract) {
        this.includeAbstract = includeAbstract;
    }

    public Boolean getIncludeMeshTerms() {
        return includeMeshTerms;
    }

    public void setIncludeMeshTerms(Boolean includeMeshTerms) {
        this.includeMeshTerms = includeMeshTerms;
    }

    public Boolean getIncludeGrants() {
        return includeGrants;
    }

    public void setIncludeGrants(Boolean includeGrants) {
        this.includeGrants = includeGrants;
    }

    public Boolean getIncludeAffiliations() {
        return includeAffiliations;
    }

    public void setIncludeAffiliations(Boolean includeAffiliations) {
        this.includeAffiliations = includeAffiliations;
    }
}