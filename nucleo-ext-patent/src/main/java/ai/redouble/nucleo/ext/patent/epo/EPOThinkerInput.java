/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.thinking.*;

/**
 * Input for EPOThinker.
 * Accepts a natural language query about patents.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-02)
 */
public class EPOThinkerInput extends ThinkerInput {
    @LLMDescription("Maximum number of patents to return (default: 5)")
    private Integer maxPatents;
    public EPOThinkerInput() {
    }
    public Integer getMaxPatents() {
        return maxPatents;
    }
    public void setMaxPatents(Integer maxPatents) {
        this.maxPatents = maxPatents;
    }
    @Override
    public String toLLMString() {
        StringBuilder sb = new StringBuilder();
        sb.append("EPO Patent Query: ").append(getQuery());
        if (maxPatents != null) {
            sb.append("\nMax patents to return: ").append(maxPatents);
        }
        return sb.toString();
    }
}
