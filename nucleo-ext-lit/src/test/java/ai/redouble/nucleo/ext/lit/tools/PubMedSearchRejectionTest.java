/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.lit.tools;

import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What an NCBI error status means to the caller. The query is forwarded unvalidated,
 * so ESearch's HTTP 400 is NCBI rejecting the query's syntax - an {@link InvalidInputException}
 * the LLM can correct and retry - while any other error status is the service failing,
 * an {@link ExternalServiceException}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class PubMedSearchRejectionTest {

    @Test
    void aSyntaxRejectionIsCorrectableSoTheCallerCanRetry() {
        LLMReadableCheckedException rejection = PubMedSearchTool.eSearchRejection(400, "cancer[MeSH");
        assertInstanceOf(InvalidInputException.class, rejection,
                "HTTP 400 is NCBI rejecting the query syntax, which the caller can correct");
        assertTrue(rejection.getMessage().contains("query"),
                "the refusal names the parameter to correct");
    }

    @Test
    void anyOtherErrorStatusIsTheServiceFailing() {
        LLMReadableCheckedException failure = PubMedSearchTool.eSearchRejection(503, "cancer");
        assertInstanceOf(ExternalServiceException.class, failure,
                "a 5xx is PubMed failing, which no corrected input fixes");
        assertTrue(failure.getMessage().contains("503"), "the failure carries the status it saw");
    }
}
