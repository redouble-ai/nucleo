/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import java.lang.annotation.*;

/**
 * Marks a field that the LLM may WRITE but must never READ back. The field stays
 * in the schema (so the model can populate it when calling a tool) and in the
 * full {@code write()} serialization (so it persists for observability), but it
 * is omitted from the model-facing serialization
 * ({@link ai.redouble.nucleo.harness.schema.NucleoJsonSerializer#writeSummarizedWithRefs} - the
 * {@code LLM_REF} mode that renders content into the prompt context).
 *
 * <p>Use for a control input the caller declares but the callee should not have
 * echoed back into its own context. The motivating case is
 * {@code ThinkerInput.artifactRefs}: the parent declares which artifacts to
 * convey to a sub-agent, the framework seeds them into the sub-agent's registry
 * (where they already render as the artifact-registry section), and re-emitting
 * the raw ref list into the sub-agent's objective would be pure duplication.
 *
 * <p>Distinct from {@code @JsonIgnore} and {@code transient}, both of which also
 * remove the field from the schema (see {@code PojoResponseHandler}), which would
 * stop the caller from ever populating it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-14)
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LLMContextIgnore {
}
