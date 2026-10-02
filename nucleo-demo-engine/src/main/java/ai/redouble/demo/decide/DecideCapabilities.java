/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.decide;

import java.util.*;

/**
 * What the decision agent is, for a page: its objective in words, the artifact type it starts
 * from and the one it answers with, and its palette, each tool with what it takes and what it
 * produces. The types are artifact aliases, the words the model's state uses.
 *
 * @param objective the objective the model reads on every turn
 * @param takes     the alias of the input artifact type
 * @param answers   the alias of the artifact type the answer is a list of
 * @param tools     the palette in order
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record DecideCapabilities(String objective, String takes, String answers, List<ToolView> tools) {
    /**
     * One tool of the palette.
     *
     * @param name        the name the model chooses it by
     * @param description what it does, from its declaration
     * @param takes       the alias of the artifact type it takes
     * @param produces    the alias of the artifact type it produces; {@code list} for a list of artifacts
     */
    public record ToolView(String name, String description, String takes, String produces) {}
}
