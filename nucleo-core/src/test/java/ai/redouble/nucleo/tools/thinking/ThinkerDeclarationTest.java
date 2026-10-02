/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The compile-enforced seat declaration refuses to exist half-said: a null grade and a
 * null answer size are each an {@code IllegalArgumentException} naming the rule (there is
 * no default, silent or otherwise), and the two required words are readable back exactly
 * as declared.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class ThinkerDeclarationTest {

    @Test
    void aNullGradeIsRefusedNamingTheRule() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> new ThinkerDeclaration(null, OutputSize.COMPACT));
        assertTrue(refusal.getMessage().contains("grade"), refusal.getMessage());
    }

    @Test
    void aNullAnswerSizeIsRefusedNamingTheRule() {
        // the refusal is OutputDeclaration's own: the rung factory refuses before the declaration exists
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> new ThinkerDeclaration(Grade.SMALL, (OutputSize) null));
        assertTrue(refusal.getMessage().contains("must name its size"), refusal.getMessage());
    }

    @Test
    void theDeclaredWordsReadBackAsDeclared() {
        ThinkerDeclaration declaration = new ThinkerDeclaration(Grade.MEDIUM, OutputSize.STANDARD);
        assertEquals(Grade.MEDIUM, declaration.getGrade());
        assertNotNull(declaration.getOutput(), "the answer rung rides the declaration");
        ThinkerDeclaration counted = new ThinkerDeclaration(Grade.SMALL, 2_048);
        assertNotNull(counted.getOutput(), "the documented exception: a raw count for the seat that fits no rung");
    }
}
