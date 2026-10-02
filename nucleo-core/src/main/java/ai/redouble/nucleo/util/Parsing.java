/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.util;

/**
 * Tolerant readings of values a model or a document spells in its own way.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-10)
 */
public final class Parsing {

    private Parsing() {
    }

    /**
     * A yes/no answer in any of its usual spellings: Y, YES, T, TRUE, 1 and N, NO, F, FALSE, 0,
     * case insensitive, surrounding whitespace ignored, read through the value's text form. Null
     * for null and for anything else.
     */
    public static Boolean yesNo(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        if (s.equalsIgnoreCase("Y") || s.equalsIgnoreCase("YES") || s.equalsIgnoreCase("T") || s.equalsIgnoreCase("TRUE") || s.equals("1")) {
            return true;
        }
        if (s.equalsIgnoreCase("N") || s.equalsIgnoreCase("NO") || s.equalsIgnoreCase("F") || s.equalsIgnoreCase("FALSE") || s.equals("0")) {
            return false;
        }
        return null;
    }
}
