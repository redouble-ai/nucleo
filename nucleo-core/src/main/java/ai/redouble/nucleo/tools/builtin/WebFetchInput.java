/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.guardrails.*;

/**
 * Input parameters for fetching web page content.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
@LLMDescription("Parameters for fetching web page content")
public class WebFetchInput implements UrlInput {
    @LLMRequired
    @LLMDescription("URL of the web page to fetch")
    @LLMExample("https://example.com/article")
    private String url;
    @LLMDescription("Maximum content length to return in characters (default: 50000)")
    private Integer maxLength;

    public WebFetchInput() {
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Integer getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(Integer maxLength) {
        this.maxLength = maxLength;
    }
}
