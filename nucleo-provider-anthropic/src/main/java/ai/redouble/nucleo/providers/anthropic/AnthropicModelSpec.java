/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.anthropic;

import ai.redouble.nucleo.harness.models.*;

/**
 * {@link ModelSpec} for Anthropic-served models (direct or Bedrock). Adds the explicit
 * prompt-cache breakpoint budget, which is an Anthropic API capability (other providers
 * either cache automatically or not at all and carry no breakpoint count).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public class AnthropicModelSpec extends AbstractModelSpec {
    private int cacheBreakpoints;

    public int getCacheBreakpoints() {return cacheBreakpoints;}

    public void setCacheBreakpoints(int cacheBreakpoints) {this.cacheBreakpoints = cacheBreakpoints;}
}
