/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import java.util.*;

/**
 * A grade's order from the page: the grade and its entries in the order of preference, the
 * first the grade's default; an empty list clears the order.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public record OrderEdit(String grade, List<String> ids) {}
