/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ai.redouble.nucleo.*;

/**
 * The model-resolution knobs: which catalog backend serves specs, which picker resolves
 * seats, and the deployment's Bedrock Mantle projects that model eligibility and request
 * routing consult.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
public class ModelSettings extends Settings {

    /** Model catalog backend. The shipped {@link JsonModelsBackend} reads the classpath catalog. */
    public volatile Class<? extends ModelsBackend> backendClass = JsonModelsBackend.class;

    /**
     * The deployment's {@link ModelPicker} as a no-arg class - the one place besides
     * {@code models.json} where model ids may legally live. A picker that needs constructor
     * arguments is declared with {@link ModelPickers#use} at startup instead.
     */
    public volatile Class<? extends ModelPicker> pickerClass = DefaultModelPicker.class;

    /**
     * Bedrock Mantle project (workspace) id whose data retention mode is
     * {@code provider_data_share}, or null in a deployment that has not provisioned one.
     * Models whose spec declares {@code requiresLax} can only be served under this project,
     * so a null makes them ineligible to every picker and refused at the request gate.
     */
    public volatile String mantleLaxProject;

    /**
     * Bedrock Mantle project (workspace) id whose data retention mode is {@code none}, or
     * null in a deployment that has not provisioned one. When set, every Mantle request for
     * a model that does not require data sharing is pinned to this project, making the
     * zero-retention guarantee independent of the account-level setting. When null, no
     * header is sent and requests resolve through the account default.
     */
    public volatile String mantleStrictProject;
}
