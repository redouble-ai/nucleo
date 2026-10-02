/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.chat;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The title cleanup a prompt cannot guarantee: {@link ConversationTitle#clean} takes the
 * first non-empty line of a model's answer, strips markdown emphasis and wrapping quotes,
 * collapses whitespace, and returns null when nothing usable is left - so a caller stores
 * no title rather than an empty one.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class ConversationTitleTest {

    @Test
    void emphasisMarkersAndWrappingQuotesAreStripped() {
        assertEquals("Heathrow Switch Connectivity Loss",
                ConversationTitle.clean("**Heathrow Switch Connectivity Loss**"),
                "markdown emphasis shows up literally in a sidebar and must go");
        assertEquals("Chunking Strategy Review",
                ConversationTitle.clean("\"Chunking Strategy Review\""),
                "a model that quoted the phrase did not title it");
        assertEquals("Mixed Wrapping",
                ConversationTitle.clean("_\"Mixed Wrapping\"_"),
                "emphasis around quotes strips layer by layer");
    }

    @Test
    void theTitleIsTheFirstNonEmptyLine() {
        assertEquals("The Title",
                ConversationTitle.clean("\n  \nThe Title\nAnd here is why I chose it..."),
                "a model that answered over several lines titled it on the first");
    }

    @Test
    void whitespaceCollapses() {
        assertEquals("A Spaced Out Title", ConversationTitle.clean("A  Spaced\t Out   Title"));
    }

    @Test
    void nothingUsableIsNull_neverAnEmptyTitle() {
        assertNull(ConversationTitle.clean(null));
        assertNull(ConversationTitle.clean("   "));
        assertNull(ConversationTitle.clean("**\"\"**"),
                "an answer that is all wrapping is no title; the caller stores none");
    }
}
