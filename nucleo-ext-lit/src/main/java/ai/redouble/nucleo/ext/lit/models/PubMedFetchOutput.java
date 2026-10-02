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
 * Output with detailed article information from PubMed.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-03)
 */
@LLMDescription("Detailed article information from PubMed including full metadata")
public class PubMedFetchOutput  {

    @LLMRequired
    @LLMDescription("List of detailed article records")
    private List<DetailedArticle> articles;

    @LLMDescription("Number of PMIDs requested")
    private Integer requestedCount;

    @LLMDescription("Number of articles successfully retrieved")
    private Integer retrievedCount;
    public List<DetailedArticle> getArticles() {
        return articles;
    }

    public void setArticles(List<DetailedArticle> articles) {
        this.articles = articles;
    }

    public Integer getRequestedCount() {
        return requestedCount;
    }

    public void setRequestedCount(Integer requestedCount) {
        this.requestedCount = requestedCount;
    }

    public Integer getRetrievedCount() {
        return retrievedCount;
    }

    public void setRetrievedCount(Integer retrievedCount) {
        this.retrievedCount = retrievedCount;
    }

    /**
     * Detailed article record with complete metadata.
     * Extends CitationArtifact to ensure it's preserved as an artifact in the LLM context.
     */
    @TypeAlias("link:cite:pubmed:full")
    @LLMDescription("Complete article record with full metadata and optional fields")
    public static class DetailedArticle extends CitationArtifact {

        @LLMRequired
        @LLMDescription("PubMed ID (PMID)")
        private String pmid;

        @LLMRequired
        @LLMDescription("List of authors with full names and affiliations")
        private List<Author> detailedAuthors;

        @LLMRequired
        @LLMDescription("Detailed journal information")
        private Journal journalInfo;

        @LLMRequired
        @LLMDescription("Publication date")
        private String publicationDate;

        @LLMDescription("PubMed Central ID (PMCID)")
        private String pmcid;

        @LLMDescription("Publication types")
        private List<String> publicationTypes;

        @LLMDescription("Medical Subject Headings (MeSH) terms")
        private List<MeshTerm> meshTerms;

        @LLMDescription("Keywords assigned to the article")
        private List<String> keywords;

        @LLMDescription("Grant and funding information")
        private List<Grant> grants;

        @LLMDescription("Citation information")
        private String citation;

        public DetailedArticle() {
            super();
            // Set source to PubMed by default
            setSource("pubmed");
        }

        public String getPmid() {
            return pmid;
        }

        public void setPmid(String pmid) {
            this.pmid = pmid;
            // Also set URL based on PMID
            if (pmid != null) {
                setUrl("https://pubmed.ncbi.nlm.nih.gov/" + pmid + "/");
            }
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
                    String name = author.getLastName();
                    if (author.getFirstName() != null) {
                        name = name + " " + author.getFirstName();
                    }
                    simpleAuthors.add(name);
                }
                setAuthors(simpleAuthors);
                // Set first author
                if (!simpleAuthors.isEmpty()) {
                    setFirstAuthor(simpleAuthors.get(0));
                }
            }
        }

        public Journal getJournalInfo() {
            return journalInfo;
        }

        public void setJournalInfo(Journal journalInfo) {
            this.journalInfo = journalInfo;
            // Also set the simple journal name in parent class
            if (journalInfo != null && journalInfo.getTitle() != null) {
                setJournal(journalInfo.getTitle());
            }
        }

        public String getPublicationDate() {
            return publicationDate;
        }

        public void setPublicationDate(String publicationDate) {
            this.publicationDate = publicationDate;
            // Extract year for parent class
            if (publicationDate != null && publicationDate.length() >= 4) {
                setYear(publicationDate.substring(0, 4));
            }
        }

        public String getPmcid() {
            return pmcid;
        }

        public void setPmcid(String pmcid) {
            this.pmcid = pmcid;
        }

        public List<String> getPublicationTypes() {
            return publicationTypes;
        }

        public void setPublicationTypes(List<String> publicationTypes) {
            this.publicationTypes = publicationTypes;
        }

        public List<MeshTerm> getMeshTerms() {
            return meshTerms;
        }

        public void setMeshTerms(List<MeshTerm> meshTerms) {
            this.meshTerms = meshTerms;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public void setKeywords(List<String> keywords) {
            this.keywords = keywords;
        }

        public List<Grant> getGrants() {
            return grants;
        }

        public void setGrants(List<Grant> grants) {
            this.grants = grants;
        }

        public String getCitation() {
            return citation;
        }

        public void setCitation(String citation) {
            this.citation = citation;
        }
    }

    /**
     * Author information with affiliations.
     */
    @LLMDescription("Author with name and optional affiliation")
    public static class Author  {
        @LLMRequired
        @LLMDescription("Last name")
        private String lastName;

        @LLMDescription("First name or initials")
        private String firstName;

        @LLMDescription("Institutional affiliation")
        private String affiliation;

        public String getLastName() {
            return lastName;
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
        }

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public String getAffiliation() {
            return affiliation;
        }

        public void setAffiliation(String affiliation) {
            this.affiliation = affiliation;
        }
    }

    /**
     * Journal information.
     */
    @LLMDescription("Journal metadata")
    public static class Journal  {
        @LLMRequired
        @LLMDescription("Journal name")
        private String title;

        @LLMDescription("Journal abbreviation")
        private String abbreviation;

        @LLMDescription("Volume number")
        private String volume;

        @LLMDescription("Issue number")
        private String issue;

        @LLMDescription("Page numbers")
        private String pages;

        @LLMDescription("ISSN identifier")
        private String issn;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getAbbreviation() {
            return abbreviation;
        }

        public void setAbbreviation(String abbreviation) {
            this.abbreviation = abbreviation;
        }

        public String getVolume() {
            return volume;
        }

        public void setVolume(String volume) {
            this.volume = volume;
        }

        public String getIssue() {
            return issue;
        }

        public void setIssue(String issue) {
            this.issue = issue;
        }

        public String getPages() {
            return pages;
        }

        public void setPages(String pages) {
            this.pages = pages;
        }

        public String getIssn() {
            return issn;
        }

        public void setIssn(String issn) {
            this.issn = issn;
        }
    }

    /**
     * MeSH term with major topic indicator.
     */
    @LLMDescription("Medical Subject Heading term")
    public static class MeshTerm  {
        @LLMRequired
        @LLMDescription("MeSH descriptor name")
        private String descriptor;

        @LLMDescription("Whether this is a major topic of the article")
        private Boolean majorTopic;

        @LLMDescription("Qualifier terms (subheadings)")
        private List<String> qualifiers;

        public String getDescriptor() {
            return descriptor;
        }

        public void setDescriptor(String descriptor) {
            this.descriptor = descriptor;
        }

        public Boolean getMajorTopic() {
            return majorTopic;
        }

        public void setMajorTopic(Boolean majorTopic) {
            this.majorTopic = majorTopic;
        }

        public List<String> getQualifiers() {
            return qualifiers;
        }

        public void setQualifiers(List<String> qualifiers) {
            this.qualifiers = qualifiers;
        }
    }

    /**
     * Grant/funding information.
     */
    @LLMDescription("Research grant or funding information")
    public static class Grant  {
        @LLMDescription("Grant ID or number")
        private String grantId;

        @LLMDescription("Funding agency")
        private String agency;

        @LLMDescription("Country of funding agency")
        private String country;

        public String getGrantId() {
            return grantId;
        }

        public void setGrantId(String grantId) {
            this.grantId = grantId;
        }

        public String getAgency() {
            return agency;
        }

        public void setAgency(String agency) {
            this.agency = agency;
        }

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }
    }
}