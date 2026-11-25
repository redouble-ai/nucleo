/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts.tools;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;

import java.util.*;

/**
 * Tool for searching artifact content by exact case-insensitive string matching.
 * Searches the whole values of every artifact in the registry, the ones a model is shown
 * as summaries included, as the serializer writes them: every text field, and every text
 * inside a list, a map or a nested object. A field marked {@code @LLMContextIgnore} is not
 * searched. Each match names the path of its text, which {@code get_artifact_field} takes
 * as a field name.
 *
 * <p>A search that matches nothing is a valid empty result, never an error. A missing
 * artifact registry is the caller's wiring fault, {@link SystemException}: the tool runs
 * only under a thinker, which injects the conversation's registry before submission.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-03)
 */
@DisplayName(value = "Search Artifacts", action = "Searching artifact content")
@ToolName("search_artifact_content")
@LLMDescription("Search artifact content by exact case-insensitive string matching across all fields. Returns matching snippets with surrounding context.")
@ReadOnly
@ToolWeight(type = ToolType.IN_MEMORY)
public class SearchArtifactContentTool extends AbstractDoer<SearchArtifactContentInput, SearchArtifactContentOutput> implements ArtifactRegistryAware {
    private static final int DEFAULT_CONTEXT_CHARS = 200;
    private static final int DEFAULT_MAX_RESULTS = 10;
    private ArtifactRegistry registry;

    public SearchArtifactContentTool(Identifiable parent) {
        super(parent);
    }

    @Override
    public void setArtifactRegistry(ArtifactRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ArtifactRegistry getArtifactRegistry() {
        return registry;
    }

    @Override
    public SearchArtifactContentOutput execute(JobContext<SearchArtifactContentOutput> context) throws LLMReadableCheckedException {
        context.publish("Starting search", 10);
        if (registry == null) {
            throw new SystemException("SearchArtifactContentTool",
                    "search_artifact_content runs only as a tool of a thinker, which injects the conversation's artifact registry before submission", null);
        }
        SearchArtifactContentOutput output = new SearchArtifactContentOutput();
        String query = input.getQuery().toLowerCase();
        // Neither carries a ceiling - how much of the registry a caller wants back is the caller's
        // to decide - but both have a floor, and below it the tool lies instead of failing. A
        // maxResults under one ends the walk before it starts and reports the empty result as
        // "found nothing"; a negative contextChars inverts the snippet window into a substring
        // whose start runs past its end.
        if (input.getMaxResults() != null && input.getMaxResults() < 1) {
            throw new InvalidInputException("maxResults", input.getMaxResults(), "Must be 1 or greater");
        }
        if (input.getContextChars() != null && input.getContextChars() < 0) {
            throw new InvalidInputException("contextChars", input.getContextChars(), "Must be 0 or greater");
        }
        int maxResults = input.getMaxResults() != null ? input.getMaxResults() : DEFAULT_MAX_RESULTS;
        int contextChars = input.getContextChars() != null ? input.getContextChars() : DEFAULT_CONTEXT_CHARS;
        List<SearchMatch> matches = new ArrayList<>();
        // Reachable artifacts (list iterands, nested and conveyed artifacts) are
        // searchable individually, so the search promise ("across all artifact
        // text") holds even though they render only through their owner.
        Map<String, Artifact> allArtifacts = registry.getAllArtifactsIncludingReachable();
        int artifactCount = 0;
        int totalArtifacts = allArtifacts.size();
        for (Map.Entry<String, Artifact> entry : allArtifacts.entrySet()) {
            if (matches.size() >= maxResults) {
                break;
            }
            Artifact artifact = entry.getValue();
            if (input.getArtifactType() != null && !matchesType(artifact, input.getArtifactType())) {
                continue;
            }
            if (input.getArtifactRef() != null) {
                String normalizedFilter = ArtifactRegistry.normalizeToKey(input.getArtifactRef());
                if (!entry.getKey().equals(normalizedFilter)) {
                    continue;
                }
            }
            searchArtifact(artifact, entry.getKey(), query, matches, maxResults, contextChars);
            artifactCount++;
            int progress = 10 + (80 * artifactCount / totalArtifacts);
            context.publish("Searching artifact " + artifactCount + "/" + totalArtifacts, progress);
        }
        output.setMatches(matches);
        output.setTotalMatches(matches.size());
        context.publish("Complete", 100);
        return output;
    }

    /**
     * Matches the query against every text of an artifact as the serializer writes it
     * whole: text fields, and the texts inside lists, maps and nested objects.
     */
    private void searchArtifact(Artifact artifact, String artifactRef, String query,
                                List<SearchMatch> matches, int maxResults, int contextChars) {
        ModelFacingFields.texts(artifact, (path, text) -> searchText(text, path, artifactRef, query, matches, maxResults, contextChars));
    }

    private void searchText(String text, String fieldPath, String artifactRef,
                            String query, List<SearchMatch> matches, int maxResults, int contextChars) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String lowerText = text.toLowerCase();
        int pos = lowerText.indexOf(query);
        while (pos >= 0 && matches.size() < maxResults) {
            SearchMatch match = new SearchMatch();
            match.setArtifactRef(artifactRef);
            match.setFieldName(fieldPath);
            match.setPosition(pos);
            match.setSnippet(extractSnippet(text, pos, query.length(), contextChars));
            matches.add(match);
            pos = lowerText.indexOf(query, pos + 1);
        }
    }

    private String extractSnippet(String text, int matchPos, int matchLen, int contextChars) {
        int start = Math.max(0, matchPos - contextChars);
        int end = Math.min(text.length(), matchPos + matchLen + contextChars);
        String snippet = text.substring(start, end);
        if (start > 0) {
            snippet = "..." + snippet;
        }
        if (end < text.length()) {
            snippet = snippet + "...";
        }
        return snippet;
    }

    private boolean matchesType(Artifact artifact, String typeFilter) {
        String className = artifact.getClass().getSimpleName().toLowerCase();
        String filter = typeFilter.toLowerCase();
        return className.contains(filter)
               || (filter.equals("cite") && className.contains("citation"))
               || (filter.equals("webpage") && className.contains("webpage"))
               || (filter.equals("code") && className.contains("code"))
               || (filter.equals("compound") && className.contains("compound"));
    }
}
