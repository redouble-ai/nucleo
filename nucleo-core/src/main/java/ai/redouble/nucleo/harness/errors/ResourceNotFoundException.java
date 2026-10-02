/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

/**
 * Exception thrown when a resource identified by a well-formed identifier does not exist.
 *
 * <p>This is a correctable exception - the LLM provided a syntactically valid identifier
 * that simply does not exist in the target system. The LLM can retry with a different
 * identifier, search for the correct one, or try an alternative tool.</p>
 *
 * <p><strong>Search vs Fetch - the key distinction:</strong></p>
 * <ul>
 *   <li>SEARCH returning 0 results = valid outcome. Return empty output (null fields).</li>
 *   <li>FETCH-BY-ID returning 404 = this exception. The identifier doesn't resolve.</li>
 * </ul>
 *
 * <p><strong>This is NOT for:</strong></p>
 * <ul>
 *   <li>Malformed identifiers (use {@link InvalidInputException})</li>
 *   <li>Search queries returning no results (return empty output - that's a valid result)</li>
 *   <li>Service unavailable (use {@link ExternalServiceException})</li>
 * </ul>
 *
 * <p><strong>This IS for:</strong></p>
 * <ul>
 *   <li>Fetch-by-ID returning 404 (e.g., PMCID not in PubMed Central)</li>
 *   <li>Patent number not found in EPO</li>
 *   <li>DOI not resolving to any document</li>
 *   <li>Any well-formed identifier that doesn't match a resource</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * if (response.statusCode() == 404) {
 *     throw new ResourceNotFoundException("PMC article", pmcid);
 * }
 * </pre>
 *
 * <p><strong>LLM sees:</strong></p>
 * <pre>
 * PMC article 'PMC1234567' was not found. Try a different identifier or use an alternative tool.
 * [This error may be correctable - you can retry with different parameters or try a different approach]
 * </pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-26)
 */
public class ResourceNotFoundException extends CorrectableLLMException {
    private final String resourceType;
    private final String identifier;

    /**
     * Constructs a resource not found exception.
     *
     * @param resourceType what kind of resource was being fetched (e.g., "PMC article", "patent", "compound")
     * @param identifier the identifier that was used to look up the resource
     */
    public ResourceNotFoundException(String resourceType, String identifier) {
        super(resourceType + " '" + identifier + "' was not found");
        this.resourceType = resourceType;
        this.identifier = identifier;
    }

    /**
     * Constructs a resource not found exception with a cause.
     *
     * @param resourceType what kind of resource was being fetched
     * @param identifier the identifier that was used to look up the resource
     * @param cause the underlying cause
     */
    public ResourceNotFoundException(String resourceType, String identifier, Throwable cause) {
        super(resourceType + " '" + identifier + "' was not found", cause);
        this.resourceType = resourceType;
        this.identifier = identifier;
    }

    @Override
    public String getLLMMessage() {
        return String.format(
            "%s '%s' was not found. Try a different identifier or use an alternative tool.",
            resourceType,
            identifier
        );
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getIdentifier() {
        return identifier;
    }
}
