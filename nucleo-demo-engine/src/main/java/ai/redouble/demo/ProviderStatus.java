/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import java.util.*;

/**
 * One provider on the status panel: its key, whether it is configured, whether a session credential
 * holds it, the human description of its credential, the credentials it declares, and its connection facts.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public record ProviderStatus(String key, boolean configured, boolean session, String credential,
                             List<CredentialView> credentials, Map<String, String> facts) {}
