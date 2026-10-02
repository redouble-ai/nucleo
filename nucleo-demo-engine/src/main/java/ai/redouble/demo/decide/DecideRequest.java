/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.decide;

/**
 * A decision agent run from the page or a curl: the folder to work on, an absolute path on
 * this machine (the shipped corpus's path is on the status).
 *
 * @param directory the folder the run starts from
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record DecideRequest(String directory) {}
