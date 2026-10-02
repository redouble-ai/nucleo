/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

/** One credential record a person provides for the session: the runtime's record id and its parts. */
public record SessionRecord(String id, String user, String secret, String host) {}
