/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts.tools;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.util.*;
import java.util.function.*;

/**
 * One artifact as the artifact tools read it: the whole values a model was shown summaries
 * of, in the form the serializer writes them ({@link NucleoJsonSerializer#modelFacingFields}).
 * A text is itself, any other value is its JSON, an artifact inside is its reference, and a
 * field marked {@code @LLMContextIgnore} is neither served nor named.
 *
 * <p>A value is addressed by a path: the field's name, in either spelling, and then, for a
 * value inside the field, the JSON pointer to it ({@code data/results/0/title}). The paths
 * {@link #texts} reports are the paths {@link #read} takes.
 *
 * @author Andrey Santrosyan
 * @since 0.1.6 (2026-11-24)
 */
final class ModelFacingFields {
    private ModelFacingFields() {
    }

    /**
     * The value at a path of an artifact, as text: a text as it is, any other value as
     * pretty-printed JSON, and null for a field that holds none.
     *
     * @param artifact the artifact to read
     * @param path the field's name, optionally followed by a JSON pointer into its value
     * @throws InvalidInputException when the path names no readable field of the artifact,
     *         or nothing inside the field; the refusal lists the fields that can be read
     */
    static String read(Artifact artifact, String path) throws InvalidInputException {
        int slash = path.indexOf('/');
        String field = NucleoJsonSerializer.serializedName(slash < 0 ? path : path.substring(0, slash));
        Set<String> names = NucleoJsonSerializer.modelFacingFieldNames(artifact.getClass());
        if (!names.contains(field)) {
            throw new InvalidInputException("fieldName", path, "a field of " + artifact.getClass().getSimpleName() + ": " + names);
        }
        JsonNode value = NucleoJsonSerializer.modelFacingFields(artifact).get(field);
        if (value == null) {
            return null;
        }
        if (slash >= 0) {
            value = value.at(path.substring(slash));
            if (value.isMissingNode()) {
                throw new InvalidInputException("fieldName", path, "a path inside " + field + "; the field itself is read as " + field);
            }
        }
        if (value.isNull()) {
            return null;
        }
        return value.isTextual() ? value.asText() : NucleoJsonSerializer.write(value);
    }

    /**
     * Hands every text of an artifact to the consumer with its path, in the order the
     * artifact is written.
     *
     * @param artifact the artifact to read
     * @param consumer takes a path and the text at it
     */
    static void texts(Artifact artifact, BiConsumer<String, String> consumer) {
        for (Map.Entry<String, JsonNode> field : NucleoJsonSerializer.modelFacingFields(artifact).properties()) {
            texts(field.getValue(), field.getKey(), consumer);
        }
    }

    private static void texts(JsonNode node, String path, BiConsumer<String, String> consumer) {
        if (node.isTextual()) {
            consumer.accept(path, node.asText());
        }
        else if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                texts(entry.getValue(), path + "/" + entry.getKey().replace("~", "~0").replace("/", "~1"), consumer);
            }
        }
        else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                texts(array.get(i), path + "/" + i, consumer);
            }
        }
    }
}
