/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import java.util.*;

/**
 * The agent's capabilities for the page: the tools in its palette and the skills it may draw on.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record AgentCapabilities(List<String> tools, List<String> skills) {}
