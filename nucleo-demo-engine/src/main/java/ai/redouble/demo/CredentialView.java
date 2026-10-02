/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import java.util.*;

/**
 * One credential a provider reads, as it declared it: the record id and its parts.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record CredentialView(String id, List<PartView> parts) {}
