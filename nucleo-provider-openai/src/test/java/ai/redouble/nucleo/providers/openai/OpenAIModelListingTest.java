/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The OpenAI listing's shape on a recorded body: ids come through, ownership rides the note,
 * and a body without the data array is refused rather than read as an empty account.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-12)
 */
class OpenAIModelListingTest {

    @Test
    void idsAndOwnershipComeThrough() throws IOException {
        List<DiscoveredModel> models = OpenAIModelListing.parse("""
                { "object": "list", "data": [
                  { "id": "gpt-5", "object": "model", "owned_by": "system" },
                  { "id": "text-embedding-3-large", "object": "model" }
                ] }
                """);
        assertEquals(2, models.size());
        assertEquals("gpt-5", models.get(0).wireModelId());
        assertEquals("owned by system", models.get(0).note());
        assertNull(models.get(1).note());
    }

    @Test
    void aBodyWithoutDataIsRefused() {
        assertThrows(IOException.class, () -> OpenAIModelListing.parse("{ \"error\": { \"message\": \"nope\" } }"));
    }
}
