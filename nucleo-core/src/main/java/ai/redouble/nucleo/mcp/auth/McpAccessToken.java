/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.auth;

import java.time.*;

/**
 * A bearer token the authorization server minted for an agent, and when it stops being
 * valid. {@code expiresAt} is null when the server sent no {@code expires_in}: the token
 * is then used until the server refuses it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public record McpAccessToken(String value, Instant expiresAt) {
    public McpAccessToken {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An access token needs a value");
        }
    }

    /** Whether the token is still usable at {@code now}; a token without an expiry always is. */
    public boolean usableAt(Instant now) {
        return expiresAt == null || now.isBefore(expiresAt);
    }

    /** The value stays out of every string a debugger or a log might render. */
    @Override
    public String toString() {
        return "McpAccessToken[" + (expiresAt == null ? "no expiry" : "expires " + expiresAt) + "]";
    }
}
