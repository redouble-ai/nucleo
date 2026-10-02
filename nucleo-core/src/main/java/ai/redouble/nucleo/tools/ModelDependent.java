/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.models.*;

/**
 * A job that holds a model seat: a thinker, the call a thinker spawns per turn, a tool whose
 * work is one exchange with a model. It declares the grade the seat needs, and it can be
 * pinned to one exact catalog entry for a run, which is how a benchmark races the same job
 * on every model of a grade. An unpinned seat resolves through the deployment's picker; a
 * pinned one skips the picker and nothing else, since the gate's other walls, kind, envelope,
 * vision and deprecation, stand for a pin as for a pick. A job that queries a database
 * depends on no model and does not implement this.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
public interface ModelDependent {
    /** The grade the seat declares; {@link Grade#CEILING} for the strongest the deployment serves. */
    Grade getGrade();

    /** Raises or lowers the declared grade for a run; a thinker refuses null. */
    void setGrade(Grade grade);

    /** Runs the seat on this exact entry instead of resolving its grade. Null unpins. */
    void pinModel(ModelSpec model);

    /** The entry the seat is pinned to, or null when it resolves its grade. */
    ModelSpec pinnedModel();

    /**
     * The seat's binding on the job's requirements: the pinned entry when there is one,
     * else the declared grade through the picker. The one place the branch is written.
     */
    default ModelBinding requireSeat(JobRequirements req, Depth depth) {
        return pinnedModel() != null ? req.requireModel(pinnedModel(), depth) : req.requireModel(getGrade(), depth);
    }
}
