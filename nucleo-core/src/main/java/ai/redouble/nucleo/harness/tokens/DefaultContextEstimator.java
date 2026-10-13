/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.llm.*;

/**
 * The estimator for a provider that states nothing of its own: the generic text rendering,
 * counted with the provider-neutral counter. A spec whose provider module is not on the
 * classpath estimates with it too.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
public class DefaultContextEstimator extends AbstractContextEstimator {
    public static final DefaultContextEstimator INSTANCE = new DefaultContextEstimator();

    public DefaultContextEstimator() {
        super(new GenericContentFormatter(), TiktokenCounter.DEFAULT);
    }
}
