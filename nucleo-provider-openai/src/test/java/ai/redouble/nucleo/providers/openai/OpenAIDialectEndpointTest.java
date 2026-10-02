/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a person pastes as an API root is parsed, never pattern-assumed: unambiguous
 * spellings (whitespace, trailing slashes) normalize, the path stays exactly as provided
 * because whether an endpoint serves under /v1 is the endpoint's business, and a root
 * without a scheme is refused with the accepted forms named - https would silently break a
 * local http server, http would silently downgrade a remote one, so neither is guessed. No
 * root at all is refused naming the credential's host part it comes from.
 *
 * @since 0.1 (2026-09-18)
 */
class OpenAIDialectEndpointTest {

    @Test
    void unambiguousSpellingsNormalizeAndThePathStaysAsProvided() {
        assertEquals("https://api.together.xyz/v1",
                new OpenAIDialectEndpoint("t", " https://api.together.xyz/v1// ", "k").getBaseUrl());
        assertEquals("http://localhost:11434/v1",
                new OpenAIDialectEndpoint("t", "http://localhost:11434/v1", "k").getBaseUrl());
        assertEquals("https://gateway.example.com/llm",
                new OpenAIDialectEndpoint("t", "https://gateway.example.com/llm", "k").getBaseUrl(),
                "a root without /v1 is the endpoint's own spelling and stays");
    }

    @Test
    void aRootWithoutASchemeIsRefusedNamingTheAcceptedForms() {
        UncorrectableRuntimeLLMException refused = assertThrows(UncorrectableRuntimeLLMException.class,
                () -> new OpenAIDialectEndpoint("t", "localhost:11434/v1", "k"));
        assertTrue(refused.getMessage().contains("carries no scheme"), refused.getMessage());
        assertTrue(refused.getMessage().contains("http://localhost:11434/v1"),
                "the refusal shows a person exactly what to provide: " + refused.getMessage());
    }

    @Test
    void noRootAtAllIsRefusedNamingTheCredentialsHostPart() {
        for (String missing : new String[] {null, "  "}) {
            UncorrectableRuntimeLLMException refused = assertThrows(UncorrectableRuntimeLLMException.class,
                    () -> new OpenAIDialectEndpoint("model listing", missing, "k"));
            assertTrue(refused.getMessage().contains("no API root was provided") && refused.getMessage().contains("host part"),
                    "the refusal says what is missing and where it comes from: " + refused.getMessage());
        }
    }
}
