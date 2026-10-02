/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.secrets;

import java.util.*;
import java.util.concurrent.*;

/**
 * The credential shapes the runtime knows, by id. A {@code ClientProvider} declares the shapes
 * of the credentials it reads when the provider registry loads it, so by the time any store
 * is asked for a provider's credential its shape is on record; an id declared by nobody - a
 * tool's own key - answers the {@link CredentialShape#generic generic} rule. Declaring one id
 * twice with the same shape is fine (two providers riding one credential each declare it);
 * with a different shape it is a defect and refused.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-23)
 */
public final class CredentialShapes {
    private static final Map<String, CredentialShape> DECLARED = new ConcurrentHashMap<>();

    private CredentialShapes() {}

    /** Puts a shape on record under its id; a second, different shape for the same id is refused. */
    public static void declare(CredentialShape shape) {
        CredentialShape existing = DECLARED.putIfAbsent(shape.id(), shape);
        if (existing != null && !existing.equals(shape)) {
            throw new IllegalStateException("The credential '" + shape.id() + "' was declared with two shapes: " + existing + " and " + shape);
        }
    }

    /** The declared shape of an id, else the generic rule. */
    public static CredentialShape of(String id) {
        CredentialShape declared = DECLARED.get(id);
        return declared != null ? declared : CredentialShape.generic(id);
    }

    /** Every declared shape, by id, for a status surface. */
    public static Map<String, CredentialShape> declared() {
        return Collections.unmodifiableMap(new TreeMap<>(DECLARED));
    }

    /** Test hook: forgets every declaration. Package-private. */
    static void reset() {
        DECLARED.clear();
    }
}
