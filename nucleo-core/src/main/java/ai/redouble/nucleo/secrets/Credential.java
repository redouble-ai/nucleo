/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.secrets;

/**
 * One credential as the runtime consumes it. Every provider client reads the secret; a few read
 * the user (the EPO consumer key, the AWS access key id) or the host (an Azure endpoint). A part
 * the store does not hold is null. Never prints its secret: {@link #toString()} shows the user and
 * the host and masks the secret.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-10)
 */
public record Credential(String user, String secret, String host) {

    /** The user and the host in clear, the secret masked, so a log line can carry a credential. */
    @Override
    public String toString() {
        return "Credential[user=" + user + ", host=" + host + ", secret=" + (secret == null ? "null" : "****") + "]";
    }
}
