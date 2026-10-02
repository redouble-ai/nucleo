/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;

import java.io.*;
import java.util.*;

/**
 * The dialect's model listing, {@code GET /models}, shared by every provider that speaks it:
 * OpenAI's own chat and embeddings providers list under one key and one root, a compatible
 * endpoint under its own. The listing carries ids and ownership only; an account's limits
 * arrive on the response headers of a call, where the endpoint sends them, which the
 * discovery's ping reads.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-12)
 */
final class OpenAIModelListing {
    static final String OPENAI_ROOT = "https://api.openai.com/v1";
    static final String MODELS_PATH = "/models";

    private OpenAIModelListing() {}

    static List<DiscoveredModel> list(String apiRoot, String apiKey) throws IOException {
        try {
            String name = apiRoot == null || apiRoot.isBlank() ? "model listing" : "model listing at " + apiRoot;
            return parse(new OpenAIDialectEndpoint(name, apiRoot, apiKey).getJsonText(MODELS_PATH));
        }
        catch (LLMReadableCheckedException e) {
            throw new IOException(e.getLLMMessage(), e);
        }
    }

    /** The listing's shape, as a pure function so it tests on a recorded body. */
    static List<DiscoveredModel> parse(String body) throws IOException {
        JsonNode data = NucleoJsonSerializer.readTree(body).get("data");
        if (data == null || !data.isArray()) {
            throw new IOException("Model listing carried no 'data' array: " + body);
        }
        List<DiscoveredModel> models = new ArrayList<>();
        for (JsonNode model : data) {
            String ownedBy = model.has("owned_by") ? model.get("owned_by").asText() : null;
            models.add(DiscoveredModel.of(model.get("id").asText(), ownedBy != null ? "owned by " + ownedBy : null));
        }
        return models;
    }
}
