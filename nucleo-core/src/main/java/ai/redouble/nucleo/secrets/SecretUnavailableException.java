/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.secrets;

/**
 * A credential the runtime asked for cannot be had: the deployment holds nothing under the id, or
 * its store cannot answer. Carries the id, and the store's own failure as the cause when there is
 * one. Unchecked, because either is a deployment fault that stops the client being built; there
 * is nothing a caller can do about it at the call site.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-10)
 */
public class SecretUnavailableException extends RuntimeException {

    private final String id;

    public SecretUnavailableException(String id, String reason) {
        super("Secret '" + id + "': " + reason);
        this.id = id;
    }

    public SecretUnavailableException(String id, String reason, Throwable cause) {
        super("Secret '" + id + "': " + reason, cause);
        this.id = id;
    }

    /** The id the runtime asked for. */
    public String getId() {
        return id;
    }
}
