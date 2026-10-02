/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.usptoodp;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.thinking.*;

/**
 * Input for UsptoOdpThinker.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class UsptoOdpThinkerInput extends ThinkerInput {
    @LLMDescription("Maximum number of patents to return (default: 5)")
    private Integer maxPatents;
    public UsptoOdpThinkerInput() {
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
        sb.append("USPTO Patent Query: ").append(getQuery());
        if (maxPatents != null) {
            sb.append("\nMax patents to return: ").append(maxPatents);
        }
        return sb.toString();
    }
}
