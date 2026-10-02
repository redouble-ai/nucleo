/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import java.util.*;

/**
 * One skill on the page: its name, description, origin, bundle id and the tools it suggests.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record SkillEntry(String name, String description, String origin, String bundleId, List<String> suggestedTools) {}
