/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.decision;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.io.*;
import java.util.*;

/**
 * The wire shape every decision model of this class speaks: TypeSafe's {@code /v1/systemone}
 * request and response, which the open replicas (Kev, Nimble, OpenJev and the rest)
 * reproduce verbatim so a client written for one serves them all. One codec here, in the
 * runtime, so the encoding a provider sends and the reading of what comes back are pinned
 * by tests that need no transport, and a provider module carries only its endpoint.
 *
 * <p>Encoding renders the state as the request gave it, a string as text and anything else
 * as the JSON the runtime's serializer writes for it, and each question under its id in the
 * shape its type takes: a choice as a {@code criteria} object of option to description, a
 * noul with an optional {@code criteria} of what true and false mean, a score as a
 * {@code criteria} array of levels.
 *
 * <p>Decoding is lenient about the extra and strict about the missing, the same rule the
 * OpenAI-dialect clients follow. Unknown fields anywhere are ignored, because the endpoints
 * of this class diverge in exactly those (a latency figure, a request id, an extra usage
 * counter). Every question asked must be answered, in the shape of the question, with the
 * option keys the question named; anything short of that is a body that is not an answer,
 * refused as an {@link IOException} naming what was missing and never quoting the state.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public final class SystemOneWire {
    /** The path every endpoint of the class serves the request on, under its API root. */
    public static final String PATH = "/v1/systemone";

    private SystemOneWire() {}

    /** What a decoded body carries besides the answers: the usage and identity the endpoint reported, each null when it sent none. */
    public record Decoded(Map<String, Answer> answers, Integer inputTokens, String servedModelId) {}

    /**
     * The request body for the wire model id and the request. A null model id leaves the
     * {@code model} field out: the body as counted for a reservation before the entry is
     * resolved, which differs from the body sent by that one field.
     */
    public static String encode(String wireModelId, DecisionRequest request) {
        ObjectNode body = NucleoJsonSerializer.createObjectNode();
        if (wireModelId != null) {
            body.put("model", wireModelId);
        }
        if (request.state() instanceof String text) {
            body.put("state", text);
        }
        else {
            body.set("state", NucleoJsonSerializer.valueToTree(request.state()));
        }
        ObjectNode questions = body.putObject("questions");
        for (Map.Entry<String, Question> entry : request.questions().entrySet()) {
            questions.set(entry.getKey(), encode(entry.getValue()));
        }
        return body.toString();
    }

    private static ObjectNode encode(Question question) {
        ObjectNode node = NucleoJsonSerializer.createObjectNode();
        node.put("instructions", question.instructions());
        switch (question) {
            case Choice choice -> {
                node.put("type", "choice");
                ObjectNode criteria = node.putObject("criteria");
                for (Map.Entry<String, String> option : choice.options().entrySet()) {
                    if (option.getValue() == null) {
                        criteria.putNull(option.getKey());
                    }
                    else {
                        criteria.put(option.getKey(), option.getValue());
                    }
                }
            }
            case Noul noul -> {
                node.put("type", "noul");
                if (noul.whenTrue() != null) {
                    ObjectNode criteria = node.putObject("criteria");
                    criteria.put("true", noul.whenTrue());
                    criteria.put("false", noul.whenFalse());
                }
            }
            case Score score -> {
                node.put("type", "score");
                ArrayNode criteria = node.putArray("criteria");
                for (String level : score.levels()) {
                    criteria.add(level);
                }
            }
        }
        return node;
    }

    /** The answers a response body carries for the request, read strictly against what was asked. */
    public static Decoded decode(String body, DecisionRequest request) throws IOException {
        JsonNode root = NucleoJsonSerializer.readTree(body);
        if (root == null || !root.isObject()) {
            throw new IOException("the response body is not a JSON object");
        }
        JsonNode answers = root.get("answers");
        if (answers == null || !answers.isObject()) {
            throw new IOException("the response carries no 'answers' object");
        }
        LinkedHashMap<String, Answer> decoded = new LinkedHashMap<>();
        for (Map.Entry<String, Question> entry : request.questions().entrySet()) {
            JsonNode node = answers.get(entry.getKey());
            if (node == null || !node.isObject()) {
                throw new IOException("the response answers no question '" + entry.getKey() + "'");
            }
            decoded.put(entry.getKey(), decode(entry.getKey(), entry.getValue(), node));
        }
        JsonNode usage = root.get("usage");
        Integer inputTokens = usage != null && usage.hasNonNull("input_tokens") && usage.get("input_tokens").canConvertToInt()
                ? usage.get("input_tokens").asInt() : null;
        String servedModelId = root.hasNonNull("model") && root.get("model").isTextual() ? root.get("model").asText() : null;
        return new Decoded(Collections.unmodifiableMap(decoded), inputTokens, servedModelId);
    }

    private static Answer decode(String id, Question question, JsonNode node) throws IOException {
        try {
            return switch (question) {
                case Choice choice -> decodeChoice(id, choice, node);
                case Noul ignored -> new NoulAnswer(number(id, node, "noul"));
                case Score score -> decodeScore(id, score, node);
            };
        }
        catch (IllegalArgumentException e) {
            throw new IOException("the answer to '" + id + "' is not one: " + e.getMessage(), e);
        }
    }

    private static ChoiceAnswer decodeChoice(String id, Choice question, JsonNode node) throws IOException {
        JsonNode probabilities = node.get("probabilities");
        if (probabilities == null || !probabilities.isObject()) {
            throw new IOException("the answer to '" + id + "' carries no 'probabilities' object");
        }
        LinkedHashMap<String, Double> distribution = new LinkedHashMap<>();
        for (String option : question.options().keySet()) {
            JsonNode probability = probabilities.get(option);
            if (probability == null || !probability.isNumber()) {
                throw new IOException("the answer to '" + id + "' carries no probability for option '" + option + "'");
            }
            distribution.put(option, probability.asDouble());
        }
        JsonNode choice = node.get("choice");
        if (choice == null || !choice.isTextual()) {
            throw new IOException("the answer to '" + id + "' names no 'choice'");
        }
        if (!question.options().containsKey(choice.asText())) {
            throw new IOException("the answer to '" + id + "' names a choice that was not among the options asked");
        }
        return new ChoiceAnswer(choice.asText(), distribution, number(id, node, "confidence"));
    }

    private static ScoreAnswer decodeScore(String id, Score question, JsonNode node) throws IOException {
        JsonNode probabilities = node.get("probabilities");
        if (probabilities == null || !probabilities.isObject()) {
            throw new IOException("the answer to '" + id + "' carries no 'probabilities' object");
        }
        List<Double> distribution = new ArrayList<>(question.levels().size());
        for (int level = 0; level < question.levels().size(); level++) {
            JsonNode probability = probabilities.get(String.valueOf(level));
            if (probability == null || !probability.isNumber()) {
                throw new IOException("the answer to '" + id + "' carries no probability for level " + level);
            }
            distribution.add(probability.asDouble());
        }
        return new ScoreAnswer(number(id, node, "score"), question.levels(), distribution, number(id, node, "confidence"));
    }

    private static double number(String id, JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IOException("the answer to '" + id + "' carries no numeric '" + field + "'");
        }
        return value.asDouble();
    }
}
