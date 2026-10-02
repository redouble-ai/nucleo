/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;

/**
 * Output from WebFetchTool - fetched web page content.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-13)
 */
@LLMDescription("Web page content fetched from a URL")
public class WebFetchOutput  {
    @LLMDescription("The fetched web page")
    private WebPageArtifact page;

    public WebFetchOutput() {
    }

    public WebPageArtifact getPage() {
        return page;
    }

    public void setPage(WebPageArtifact page) {
        this.page = page;
    }
}
