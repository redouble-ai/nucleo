/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;
import java.util.*;

/**
 * Artifact representing a citation to a scholarly work (paper, preprint, book, etc.).
 *
 * <p>This is the base class for all citation-like entities that should be preserved
 * across agent workflows. Specific implementations like PubMedArticle or BioRxivPreprint
 * should extend this class.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-13)
 */
@TypeAlias("link:cite")
public class CitationArtifact extends LinkArtifact {
    @LLMDescription("First author in LastName FirstInitial format (e.g., 'Smith J')")
    private String firstAuthor;
    @LLMDescription("List of all authors")
    private List<String> authors;
    @LLMDescription("Publication year (e.g., '2023')")
    private String year;
    @LLMDescription("Journal or venue name")
    private String journal;
    @LLMDescription("Digital Object Identifier")
    private String doi;
    @LLMDescription("Abstract or summary of the work")
    @LLMSummarizable("scientific paper abstract")
    private String abstractText;
    @LLMDescription("Source database (e.g., 'pubmed', 'biorxiv', 'medrxiv')")
    private String source;
    @LLMDescription("Brief description of why this article is relevant and what it contributes")
    private String relevance;
    public CitationArtifact() {
    }
    public String getFirstAuthor() {
        return firstAuthor;
    }
    public void setFirstAuthor(String firstAuthor) {
        this.firstAuthor = firstAuthor;
    }
    public List<String> getAuthors() {
        return authors;
    }
    public void setAuthors(List<String> authors) {
        this.authors = authors;
    }
    public String getYear() {
        return year;
    }
    public void setYear(String year) {
        this.year = year;
    }
    public String getJournal() {
        return journal;
    }
    public void setJournal(String journal) {
        this.journal = journal;
    }
    public String getDoi() {
        return doi;
    }
    public void setDoi(String doi) {
        this.doi = doi;
        // Auto-generate preprint server URLs for bioRxiv/medRxiv DOIs
        if (doi != null && doi.startsWith("10.1101/")) {
            if ("biorxiv".equalsIgnoreCase(this.source)) {
                setUrl("https://www.biorxiv.org/content/" + doi);
            }
            else if ("medrxiv".equalsIgnoreCase(this.source)) {
                setUrl("https://www.medrxiv.org/content/" + doi);
            }
        }
    }
    public String getAbstractText() {
        return abstractText;
    }
    public void setAbstractText(String abstractText) {
        this.abstractText = abstractText;
    }
    public String getSource() {
        return source;
    }
    public void setSource(String source) {
        this.source = source;
    }
    public String getRelevance() {
        return relevance;
    }
    public void setRelevance(String relevance) {
        this.relevance = relevance;
    }

    /**
     * Returns a formatted citation string for display purposes.
     * Format: FirstAuthor et al. (Year) Title. Journal.
     */
    public String getFormattedCitation() {
        StringBuilder sb = new StringBuilder();
        if (firstAuthor != null) {
            sb.append(firstAuthor);
            if (authors != null && authors.size() > 1) {
                sb.append(" et al.");
            }
        }
        if (year != null) {
            sb.append(" (").append(year).append(")");
        }
        String title = getTitle();
        if (title != null) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(title);
            if (!title.endsWith(".")) sb.append(".");
        }
        if (journal != null) {
            sb.append(" ").append(journal).append(".");
        }
        return sb.toString();
    }
}
