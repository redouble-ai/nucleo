/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.sommelier;

import ai.redouble.nucleo.harness.schema.*;

import java.util.*;

/**
 * What a {@link Sommelier} answers: the names of the tools that fit the need. An empty
 * list says that every tool was read and none fits.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
public class SommelierAnswer {
    @LLMDescription("The name of every tool that fits the need, spelled exactly as the input spells it. Empty when no tool fits.")
    private List<String> fitting;

    public List<String> getFitting() {
        return fitting;
    }

    public void setFitting(List<String> fitting) {
        this.fitting = fitting;
    }
}
