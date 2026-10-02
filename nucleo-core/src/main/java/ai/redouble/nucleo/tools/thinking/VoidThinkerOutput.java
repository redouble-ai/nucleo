/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Empty thinker output for message-driven thinkers like ReactiveThinker.
 * Summary is typically null since responses go via streaming.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
public class VoidThinkerOutput extends ThinkerOutput<Reasoning> {
}
