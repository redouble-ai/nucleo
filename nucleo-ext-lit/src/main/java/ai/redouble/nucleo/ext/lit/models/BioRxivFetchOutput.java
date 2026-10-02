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
 * Output with detailed preprint information from bioRxiv/medRxiv.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-12)
 */
@LLMDescription("Detailed preprint information from bioRxiv/medRxiv including full metadata")
public class BioRxivFetchOutput  {

    @LLMRequired
    @LLMDescription("Detailed preprint record with complete metadata")
    private DetailedPreprint preprint;

    public DetailedPreprint getPreprint() {
        return preprint;
    }

    public void setPreprint(DetailedPreprint preprint) {
        this.preprint = preprint;
    }

    /**
     * Detailed preprint record with complete metadata.
     * Extends CitationArtifact to ensure it's preserved as an artifact in the LLM context.
     */
    @TypeAlias("link:cite:biorxiv:full")
    @LLMDescription("Complete preprint record with full metadata and optional fields")
    public static class DetailedPreprint extends CitationArtifact {

        @LLMRequired
        @LLMDescription("List of authors with detailed information")
        private List<Author> detailedAuthors;

        @LLMRequired
        @LLMDescription("Posting date (YYYY-MM-DD format)")
        private String date;

        @LLMRequired
        @LLMDescription("Subject category")
        private String category;

        @LLMRequired
        @LLMDescription("Server: 'biorxiv' or 'medrxiv'")
        private String server;

        @LLMDescription("Preprint version number")
        private String version;

        @LLMDescription("Article type (e.g., 'new results', 'confirmatory results')")
        private String type;

        @LLMDescription("License information (e.g., 'cc_by', 'cc_by_nc_nd')")
        private String license;

        @LLMDescription("Corresponding author name")
        private String authorCorresponding;

        @LLMDescription("Corresponding author institution")
        private String authorCorrespondingInstitution;

        @LLMDescription("Funding information")
        private List<FundingInfo> funding;

        @LLMDescription("Published article DOI if the preprint has been published")
        private String publishedDoi;

        @LLMDescription("Path to JATS XML file")
        private String jatsXmlPath;

        public DetailedPreprint() {
            super();
            // Source will be set when server is set
        }

        public List<Author> getDetailedAuthors() {
            return detailedAuthors;
        }

        public void setDetailedAuthors(List<Author> detailedAuthors) {
            this.detailedAuthors = detailedAuthors;
            // Also set the simple authors list in parent class
            if (detailedAuthors != null && !detailedAuthors.isEmpty()) {
                List<String> simpleAuthors = new ArrayList<>();
                for (Author author : detailedAuthors) {
                    simpleAuthors.add(author.getName());
                }
                setAuthors(simpleAuthors);
                // Set first author
                if (!simpleAuthors.isEmpty()) {
                    setFirstAuthor(simpleAuthors.get(0));
                }
            }
        }

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
            // Extract year for parent class (YYYY-MM-DD format)
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
            // Also set the source in parent class
            setSource(server);
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getLicense() {
            return license;
        }

        public void setLicense(String license) {
            this.license = license;
        }

        // AbstractText getter/setter inherited from CitationArtifact

        public String getAuthorCorresponding() {
            return authorCorresponding;
        }

        public void setAuthorCorresponding(String authorCorresponding) {
            this.authorCorresponding = authorCorresponding;
        }

        public String getAuthorCorrespondingInstitution() {
            return authorCorrespondingInstitution;
        }

        public void setAuthorCorrespondingInstitution(String authorCorrespondingInstitution) {
            this.authorCorrespondingInstitution = authorCorrespondingInstitution;
        }

        public List<FundingInfo> getFunding() {
            return funding;
        }

        public void setFunding(List<FundingInfo> funding) {
            this.funding = funding;
        }

        public String getPublishedDoi() {
            return publishedDoi;
        }

        public void setPublishedDoi(String publishedDoi) {
            this.publishedDoi = publishedDoi;
        }

        public String getJatsXmlPath() {
            return jatsXmlPath;
        }

        public void setJatsXmlPath(String jatsXmlPath) {
            this.jatsXmlPath = jatsXmlPath;
        }
    }

    /**
     * Author information.
     */
    @LLMDescription("Author name")
    public static class Author  {
        @LLMRequired
        @LLMDescription("Full author name")
        private String name;

        @LLMDescription("ORCID identifier")
        private String orcid;

        @LLMDescription("Institutional affiliation")
        private String institution;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getOrcid() {
            return orcid;
        }

        public void setOrcid(String orcid) {
            this.orcid = orcid;
        }

        public String getInstitution() {
            return institution;
        }

        public void setInstitution(String institution) {
            this.institution = institution;
        }
    }

    /**
     * Funding information.
     */
    @LLMDescription("Research funding information")
    public static class FundingInfo  {
        @LLMDescription("Funder name")
        private String name;

        @LLMDescription("Funder ID")
        private String id;

        @LLMDescription("ID type (e.g., 'ROR', 'Crossref Funder ID')")
        private String idType;

        @LLMDescription("Award or grant number")
        private String award;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getIdType() {
            return idType;
        }

        public void setIdType(String idType) {
            this.idType = idType;
        }

        public String getAward() {
            return award;
        }

        public void setAward(String award) {
            this.award = award;
        }
    }
}
