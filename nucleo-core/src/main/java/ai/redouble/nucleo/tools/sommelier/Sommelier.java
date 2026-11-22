/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.sommelier;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.registry.*;

import java.util.*;

/**
 * Pairs a stated need with the tools of a catalog too long to list to a model. A thinker
 * whose catalog is longer than {@link SommelierSettings#listedTools} sees no tool names:
 * it states a need to {@code find_tools}, and the deployment's sommelier names the tools
 * that fit.
 *
 * <p>The seam is one method that returns a job. {@link FindToolsTool} submits that job as
 * its child and waits for it, so an implementation is free to be one model call, several,
 * a search over an index, or anything else a job can do. {@link ModelSommelier} is the
 * implementation the runtime starts with; a deployment replaces it through
 * {@code ToolHub.getInstance().setSommelier(...)}.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
public interface Sommelier {

    /**
     * The job that answers which tools of the catalog fit the need. It is built here and
     * run by the caller. Its answer names tools as the catalog names them; a name the
     * catalog does not hold is dropped by the caller. A lookup that cannot be completed
     * fails the job, and never answers with an empty list in place of the failure.
     *
     * @param parent the job the returned job runs under
     * @param need what the asking model has and what it wants, in its own words
     * @param catalog every tool on offer to the asking thinker
     */
    Job<SommelierAnswer> pair(Identifiable parent, String need, Collection<ToolProvider> catalog) throws LLMReadableCheckedException;
}
