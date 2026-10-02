/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.systemone;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;

import java.io.*;
import java.util.*;

/**
 * The System One wire's model listing, {@code GET /v1/models}: the names the endpoint answers
 * decisions under, and for a server that says so (Kev does), what stands behind each name:
 * the run it loaded, the base model, the device, backend and precision it computes in, and
 * the temperature its probabilities are calibrated at. Those are the facts a person wants to
 * see next to a credential, since the wire's answers name the alias ({@code kev-latest}),
 * never the run.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
final class SystemOneModelListing {
    static final String MODELS_PATH = "/v1/models";

    /**
     * One served name and what the endpoint said about it.
     *
     * @param name        the id the endpoint answers decisions under
     * @param description the endpoint's own words, null when it gave none
     * @param run         the checkpoint loaded behind the name, null when the listing does not say
     * @param base        the base model, null when the listing does not say
     * @param device      the device the endpoint computes on, null when the listing does not say
     * @param backend     the compute backend, null when the listing does not say
     * @param dtype       the precision, null when the listing does not say
     * @param temperature the calibration temperature, null when the listing does not say
     */
    record Served(String name, String description, String run, String base, String device, String backend, String dtype,
                  Double temperature) {
        /** The facts in one line for a person: {@code jaredpalmer/kev-9b on mps (mlx, bfloat16), temperature 2.30}. */
        String facts() {
            StringBuilder line = new StringBuilder();
            if (run != null) {
                line.append(run);
            }
            if (device != null || backend != null || dtype != null) {
                line.append(run != null ? " on " : "on ");
                if (device != null) {
                    line.append(device);
                }
                List<String> compute = new ArrayList<>();
                if (backend != null) {
                    compute.add(backend);
                }
                if (dtype != null) {
                    compute.add(dtype);
                }
                if (!compute.isEmpty()) {
                    line.append(device != null ? " (" : "(").append(String.join(", ", compute)).append(')');
                }
            }
            if (temperature != null) {
                line.append(line.isEmpty() ? "" : ", ").append("temperature ").append(String.format(Locale.ROOT, "%.2f", temperature));
            }
            return line.isEmpty() ? (description != null ? description : name) : line.toString();
        }

        /**
         * What a person connecting the endpoint wants to see, in one line: which model it
         * loaded, on what device, at what precision - {@code jaredpalmer/kev-9b on mps, bfloat16}.
         * The backend and the calibration temperature stay in {@link #facts()}, the catalog
         * note's line.
         */
        String summary() {
            StringBuilder line = new StringBuilder(run != null ? run : name);
            if (device != null) {
                line.append(" on ").append(device);
            }
            if (dtype != null) {
                line.append(", ").append(dtype);
            }
            return line.toString();
        }

        DiscoveredModel discovered() {
            String note = description != null && !description.equals(facts()) ? description : facts();
            return DiscoveredModel.of(name, note);
        }
    }

    private SystemOneModelListing() {}

    static List<Served> list(String apiRoot, String apiKey) throws IOException {
        try {
            return parse(new SystemOneEndpoint("model listing at " + apiRoot, apiRoot, apiKey).get(MODELS_PATH));
        }
        catch (LLMReadableCheckedException e) {
            throw new IOException(e.getLLMMessage(), e);
        }
    }

    /** The listing's shape, as a pure function so it tests on a recorded body. */
    static List<Served> parse(JsonNode body) throws IOException {
        JsonNode models = body.get("models");
        if (models == null || !models.isArray()) {
            throw new IOException("Model listing carried no 'models' array: " + body);
        }
        List<Served> served = new ArrayList<>();
        for (JsonNode model : models) {
            String name = text(model, "name");
            if (name == null) {
                name = text(model, "id");
            }
            if (name == null) {
                throw new IOException("A listed model carries neither 'name' nor 'id': " + model);
            }
            JsonNode temperature = model.get("temperature");
            served.add(new Served(name, text(model, "description"), text(model, "run"), text(model, "base"), text(model, "device"),
                    text(model, "backend"), text(model, "dtype"), temperature != null && temperature.isNumber() ? temperature.asDouble() : null));
        }
        return served;
    }

    static List<Served> parse(String body) throws IOException {
        return parse(NucleoJsonSerializer.readTree(body));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
