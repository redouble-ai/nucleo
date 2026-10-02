/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;
import java.util.*;

/**
 * Artifact representing a person entity (author, researcher, contributor, etc.).
 *
 * <p>When serialized to LLM, instances will be replaced with references like:
 * «artifact:person~d4e5f6»
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-13)
 */
@TypeAlias("person")
public class PersonArtifact extends AbstractArtifact {
    @LLMDescription("Full name of the person")
    private String name;

    @LLMDescription("Professional title (e.g., 'Dr.', 'Prof.')")
    private String title;

    @LLMDescription("Primary institutional affiliation")
    private String affiliation;

    @LLMDescription("Email address")
    private String email;

    @LLMDescription("ORCID identifier")
    private String orcid;

    @LLMDescription("Areas of expertise or research interests")
    private List<String> expertiseAreas;

    @LLMDescription("Role in the current context (e.g., 'corresponding author', 'principal investigator')")
    private String role;

    public PersonArtifact() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAffiliation() {
        return affiliation;
    }

    public void setAffiliation(String affiliation) {
        this.affiliation = affiliation;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getOrcid() {
        return orcid;
    }

    public void setOrcid(String orcid) {
        this.orcid = orcid;
    }

    public List<String> getExpertiseAreas() {
        return expertiseAreas;
    }

    public void setExpertiseAreas(List<String> expertiseAreas) {
        this.expertiseAreas = expertiseAreas;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    /**
     * Returns a formatted display string for the person.
     * Format: [Title] Name, Affiliation
     */
    public String getDisplayName() {
        StringBuilder sb = new StringBuilder();
        if (title != null) {
            sb.append(title).append(" ");
        }
        if (name != null) {
            sb.append(name);
        }
        if (affiliation != null) {
            sb.append(", ").append(affiliation);
        }
        return sb.toString();
    }
}