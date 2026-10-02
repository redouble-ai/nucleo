/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;

/**
 * The picker base for deployments where zero data retention is non-negotiable: every
 * answer - pick and failover substitute alike - must be Bedrock-hosted (the account
 * runs retention mode {@code none}) and must not require the LAX data-share posture
 * ({@link ModelSpec#requiresLax()}: the one path on Bedrock where prompts reach the
 * model provider). A subclass whose pins drift into a non-ZDR spec fails at resolution,
 * loudly, regardless of what the compliance envelope would say - the family enforces
 * its own promise.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-04)
 */
public abstract class ZdrOnlyModelPicker extends BedrockOnlyModelPicker {

    @Override
    protected void vetPick(ModelSpec picked, Seat seat, Situation situation) {
        if (!zdr(picked)) {
            throw new UncorrectableRuntimeLLMException("Picker " + getClass().getSimpleName()
                    + " is ZDR-only, but picked " + picked.getId() + " for seat " + seat.jobClass().getSimpleName()
                    + ": " + (picked.isBedrockHosted()
                            ? "the spec requires the LAX data-share posture - prompts would reach the model provider"
                            : "the route is not Bedrock-hosted, so our zero-retention account mode does not cover it"));
        }
    }

    @Override
    protected boolean eligibleForFailover(ModelSpec candidate, Situation situation) {
        return zdr(candidate);
    }

    private static boolean zdr(ModelSpec spec) {
        return spec.isBedrockHosted() && !spec.requiresLax();
    }
}
