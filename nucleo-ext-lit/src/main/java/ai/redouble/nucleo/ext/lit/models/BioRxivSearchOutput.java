/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit.models;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * Output from bioRxiv/medRxiv preprint search.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-12)
 */
@LLMDescription("Results from bioRxiv/medRxiv preprint search")
public class BioRxivSearchOutput  {

    @LLMRequired
    @LLMDescription("List of preprints found, ordered by date or relevance")
    private List<BioRxivPreprint> preprints;

    @LLMRequired
    @LLMDescription("Total number of preprints matching the query (may exceed returned results)")
    private Integer totalCount;

    @LLMDescription("The search query that was executed")
    private String queryExecuted;

    @LLMDescription("Whether results were truncated due to maxResults limit")
    private Boolean truncated;

    @LLMDescription("Which servers were searched (biorxiv, medrxiv, or both)")
    private String serversSearched;

    public List<BioRxivPreprint> getPreprints() {
        return preprints;
    }

    public void setPreprints(List<BioRxivPreprint> preprints) {
        this.preprints = preprints;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public String getQueryExecuted() {
        return queryExecuted;
    }

    public void setQueryExecuted(String queryExecuted) {
        this.queryExecuted = queryExecuted;
    }

    public Boolean getTruncated() {
        return truncated;
    }

    public void setTruncated(Boolean truncated) {
        this.truncated = truncated;
    }

    public String getServersSearched() {
        return serversSearched;
    }

    public void setServersSearched(String serversSearched) {
        this.serversSearched = serversSearched;
    }

    /**
     * Individual bioRxiv/medRxiv preprint with metadata.
     * Extends CitationArtifact to enable preservation across agent workflows.
     */
    @TypeAlias("link:cite:biorxiv")
    @LLMDescription("Individual preprint article with bibliographic metadata")
    public static class BioRxivPreprint extends CitationArtifact {

        @LLMRequired
        @LLMDescription("Preprint posting date (YYYY-MM-DD format)")
        private String date;

        @LLMRequired
        @LLMDescription("Subject category (e.g., 'Cell Biology', 'Neuroscience')")
        private String category;

        @LLMRequired
        @LLMDescription("Server where posted: 'biorxiv' or 'medrxiv'")
        private String server;

        @LLMDescription("Preprint version (e.g., '1', '2', '3')")
        private String version;

        @LLMDescription("Corresponding author name")
        private String correspondingAuthor;

        @LLMDescription("Corresponding author institution")
        private String correspondingInstitution;

        @LLMDescription("Published article DOI if the preprint has been published")
        private String publishedDoi;

        public BioRxivPreprint() {
            super();
            // Source will be set by the server field
        }

        // Override setDoi to also set URL
        @Override
        public void setDoi(String doi) {
            super.setDoi(doi);
            // Set URL based on DOI and server
            if (doi != null && doi.startsWith("10.1101/") && server != null) {
                if ("biorxiv".equalsIgnoreCase(server)) {
                    setUrl("https://www.biorxiv.org/content/" + doi);
                } else if ("medrxiv".equalsIgnoreCase(server)) {
                    setUrl("https://www.medrxiv.org/content/" + doi);
                }
            }
        }

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
            // Extract year from date for CitationArtifact's year field
            if (date != null && date.length() >= 4) {
                setYear(date.substring(0, 4));
            }
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getServer() {
            return server;
        }

        public void setServer(String server) {
            this.server = server;
            // Also set the source field in CitationArtifact
            setSource(server);
            // Update URL if DOI is already set
            if (getDoi() != null) {
                setDoi(getDoi());  // Re-trigger URL generation
            }
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getCorrespondingAuthor() {
            return correspondingAuthor;
        }

        public void setCorrespondingAuthor(String correspondingAuthor) {
            this.correspondingAuthor = correspondingAuthor;
        }

        public String getCorrespondingInstitution() {
            return correspondingInstitution;
        }

        public void setCorrespondingInstitution(String correspondingInstitution) {
            this.correspondingInstitution = correspondingInstitution;
        }

        public String getPublishedDoi() {
            return publishedDoi;
        }

        public void setPublishedDoi(String publishedDoi) {
            this.publishedDoi = publishedDoi;
        }
    }
}
