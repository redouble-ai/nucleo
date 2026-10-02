/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.tools.benchmark.*;

/** What a benchmark produced: the reference run's answer and the report the runtime also logged as a table. */
public record BenchmarkOutcome(DemoAnswer answer, BenchmarkReport report) {}
