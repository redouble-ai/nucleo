/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

/**
 * The test suite's picker: the shipped {@link DefaultModelPicker} over the test catalog's
 * {@code pins}, with every entry callable. The suite makes no model call, carries no
 * provider artifact and holds no credential, so "can this deployment call it" is answered
 * yes for every entry, and every route reports unthrottled, so the limiter state other
 * tests leave behind never diverts a pick to a sibling route; everything else - the order,
 * the fill-in, the walk, the envelope, the probe, the roles - is the shipped picker's.
 * Named by {@code TestConfigurator} as the suite's {@code ModelSettings.pickerClass}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-11-09)
 */
public class SuiteModelPicker extends DefaultModelPicker {
    @Override
    protected double routeSlowdown(ModelSpec spec) {
        return 1.0;
    }

    @Override
    protected String whyNotProvisioned(ModelSpec candidate) {
        return null;
    }

    @Override
    protected String whyNotCallable(ModelSpec candidate) {
        return null;
    }
}
