/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.models.*;

/**
 * The admission account for a model endpoint bounded by how many requests it works on at
 * once rather than by a quota window: a model served from a machine the deployment owns,
 * whose catalog entry declares {@code max_concurrent}. One permit is one request in flight,
 * held from admission to the end of the call and returned then, exactly as a
 * {@link DatabaseGate} permit is one held connection. Named after the entry's id, so the
 * health snapshot shows the model under the same name whichever kind of account it has.
 *
 * <p>Nothing adaptive rides on it: a server that takes one request at a time is not
 * throttled by anyone's headers, and a caller past the capacity does not fail, it waits in
 * admission holding nothing.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public class ModelGate extends CountingGate {

    private final String modelId;

    /** A gate of the entry's declared capacity, named after the entry. */
    public ModelGate(ModelSpec model) {
        super(capacityOf(model));
        this.modelId = model.getId();
    }

    private static int capacityOf(ModelSpec model) {
        if (model.getMaxConcurrent() == null) {
            throw new IllegalArgumentException("Model " + model.getId() + " declares no max_concurrent; its account is a token bucket, not a gate");
        }
        return model.getMaxConcurrent();
    }

    @Override
    public String limiterName() {
        return modelId;
    }
}
