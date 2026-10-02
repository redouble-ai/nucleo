/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.decision;

import java.util.*;

/**
 * Pick one of the options the caller names. Each option is a key the answer comes back
 * under, with a description the model reads or null when the key says enough. The order is
 * kept as given, because decision models read the options as a list and the order they were
 * asked in is part of the record of a run.
 *
 * @param instructions what is being asked
 * @param options      option key to description, at least one; a description may be null
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record Choice(String instructions, Map<String, String> options) implements Question {

    public Choice {
        Question.requireInstructions(instructions);
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("A choice needs at least one option");
        }
        LinkedHashMap<String, String> kept = new LinkedHashMap<>();
        for (Map.Entry<String, String> option : options.entrySet()) {
            if (option.getKey() == null || option.getKey().isBlank()) {
                throw new IllegalArgumentException("A choice option needs a key");
            }
            kept.put(option.getKey(), option.getValue());
        }
        options = Collections.unmodifiableMap(kept);
    }

    /** The options as keys alone, in order, for a caller whose keys say enough. */
    public static Choice of(String instructions, String... keys) {
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        for (String key : keys) {
            options.put(key, null);
        }
        return new Choice(instructions, options);
    }
}
