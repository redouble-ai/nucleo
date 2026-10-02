/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema.unaliased;

import ai.redouble.nucleo.harness.artifacts.*;

/**
 * A concrete artifact class with no {@code @TypeAlias}, in a package of its own, so a registry
 * scan over this root can be asked to refuse it by name.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
public class Bare extends AbstractArtifact {
}
