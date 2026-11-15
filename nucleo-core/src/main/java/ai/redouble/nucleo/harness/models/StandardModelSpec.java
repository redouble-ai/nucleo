/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;


/**
 * The {@link ModelSpec} of every entry no other class claims: the fields of the common base
 * and the price rule of the four prices. It fits any entry, at the lowest answer a class can
 * give, so a catalog always loads and a class that recognizes anything in an entry is kept
 * over it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-21)
 */
public class StandardModelSpec extends AbstractModelSpec {
    @Override
    public int match() {
        return 1;
    }
}
