/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.sommelier;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Input of {@link FindToolsTool}.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
public class FindToolsInput {
    @LLMDescription("What you have and what you want, in a sentence or two of your own words. Say what the task is about; do not guess at tool names.")
    private String need;

    public String getNeed() {
        return need;
    }

    public void setNeed(String need) {
        this.need = need;
    }
}
