/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

/**
 * What connecting did: the provider that verified the credential with its listed count, or its
 * failure in the provider's own words, or a note when nothing on the credential can list; and
 * the fresh status the page re-renders from.
 */
public record ConnectOutcome(String verifiedBy, Integer listed, String failure, String note, RuntimeStatus status) {}
