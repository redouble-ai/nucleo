/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.util;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Parsing#yesNo}: Y, YES, T, TRUE and 1 read as true; N, NO, F, FALSE and 0 read as false;
 * case does not matter and surrounding whitespace is ignored; null and any other spelling read as
 * null, never as a guess.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class ParsingTest {

    @Test
    void theUsualSpellingsOfYesReadAsTrue() {
        for (String yes : new String[] {"Y", "YES", "T", "TRUE", "1", "yes", "true", "  y  "}) {
            assertEquals(Boolean.TRUE, Parsing.yesNo(yes), "'" + yes + "' is a yes in any case, whitespace ignored");
        }
    }

    @Test
    void theUsualSpellingsOfNoReadAsFalse() {
        for (String no : new String[] {"N", "NO", "F", "FALSE", "0", "no", "false", "  n  "}) {
            assertEquals(Boolean.FALSE, Parsing.yesNo(no), "'" + no + "' is a no in any case, whitespace ignored");
        }
    }

    @Test
    void anyOtherValueReadsAsNull() {
        assertNull(Parsing.yesNo(null), "null in, null out");
        assertNull(Parsing.yesNo("maybe"), "an unrecognised spelling is not guessed at");
        assertNull(Parsing.yesNo(""), "empty text is not an answer");
        assertNull(Parsing.yesNo("2"), "only 1 and 0 are the numeric spellings");
    }

    @Test
    void aNonStringValueIsReadThroughItsTextForm() {
        assertEquals(Boolean.TRUE, Parsing.yesNo(1), "the integer 1 spells yes");
        assertEquals(Boolean.FALSE, Parsing.yesNo(Boolean.FALSE), "a Boolean spells itself");
    }
}
