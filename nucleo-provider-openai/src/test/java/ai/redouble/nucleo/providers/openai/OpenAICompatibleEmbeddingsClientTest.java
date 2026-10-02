/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import org.junit.jupiter.api.*;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@link OpenAICompatibleEmbeddingsClient}'s response parsing without a transport, mirroring
 * the Bedrock artifact's {@code BedrockCohereEmbeddingsClientTest}: the vector rides {@code data[0].embedding},
 * and anything else fails loud rather than producing a wrong-width vector.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-30)
 */
public class OpenAICompatibleEmbeddingsClientTest {

    @Test
    void parsesTheEmbeddingsResponseShape() throws IOException {
        float[] v = OpenAICompatibleEmbeddingsClient.parseEmbedding(
                "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,"
                        + "\"embedding\":[0.25,-0.5,1.0]}],\"model\":\"text-embedding-3-small\"}");
        assertArrayEquals(new float[]{0.25f, -0.5f, 1.0f}, v);
    }

    @Test
    void responseWithoutDataFailsLoud() {
        assertThrows(RuntimeException.class,
                () -> OpenAICompatibleEmbeddingsClient.parseEmbedding("{\"error\":{\"message\":\"boom\"}}"));
    }

    @Test
    void nonJsonBodyFailsLoud() {
        assertThrows(IOException.class,
                () -> OpenAICompatibleEmbeddingsClient.parseEmbedding("<html>gateway error</html>"));
    }
}
