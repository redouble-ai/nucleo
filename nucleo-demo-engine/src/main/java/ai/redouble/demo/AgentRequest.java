/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.harness.models.*;

/** The agent's query and, when the caller names one, the rung it runs at; null runs it at the agent's declared grade. */
public record AgentRequest(String query, Grade grade) {}
