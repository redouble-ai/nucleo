/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.conversation.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static ai.redouble.nucleo.harness.conversation.Cacheability.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The default choice of the turns that carry a prompt-cache breakpoint: the order the
 * allowance is spent in, what {@code NEVER} and {@code UNLIKELY} forbid, and the turns marked
 * beside the end for a provider that needs them.
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-11)
 */
class CacheBreakpointPlacementTest {

    private static List<ProcessedMessageData> turns(Cacheability... stated) {
        List<ProcessedMessageData> turns = new ArrayList<>();
        for (Cacheability cacheability : stated) {
            turns.add(ProcessedMessageData.textOnly(TurnRole.USER, "turn", cacheability));
        }
        return turns;
    }

    private static List<Boolean> all(int size) {
        return Collections.nCopies(size, true);
    }

    private static Set<Integer> place(int allowance, int trail, Cacheability... stated) {
        return CacheBreakpointPlacement.turns(turns(stated), all(stated.length), allowance, trail);
    }

    @Test
    void withNoTrail_theEndAndTheFirstTurnAreChosen() {
        assertEquals(Set.of(0, 4), place(3, 0, AUTO, AUTO, AUTO, AUTO, AUTO));
    }

    @Test
    void aTrailMarksTheTurnsBeforeTheEnd_aheadOfTheFirstTurn() {
        assertEquals(Set.of(0, 3, 4), place(3, 1, AUTO, AUTO, AUTO, AUTO, AUTO), "the end, the turn before it, then the first");
        assertEquals(Set.of(3, 4), place(2, 1, AUTO, AUTO, AUTO, AUTO, AUTO), "the trail is spent before the first turn");
        assertEquals(Set.of(2, 3, 4), place(3, 2, AUTO, AUTO, AUTO, AUTO, AUTO), "a longer trail takes what the first turn would have had");
    }

    @Test
    void theTrailPassesOverTurnsThatMayNotEndAPrefixOrCannotCarryABreakpoint() {
        assertEquals(Set.of(0, 1, 4), place(3, 1, AUTO, AUTO, UNLIKELY, UNLIKELY, AUTO), "an UNLIKELY turn ends nothing, as the trail or as the end");
        List<ProcessedMessageData> turns = turns(AUTO, AUTO, AUTO, AUTO);
        assertEquals(Set.of(0, 1, 3), CacheBreakpointPlacement.turns(turns, List.of(true, true, false, true), 3, 1), "a turn that carries none is passed over");
    }

    @Test
    void theTrailComesAheadOfPreferredTurns_andAfterAlwaysTurnsAndTheEnd() {
        assertEquals(Set.of(1, 4, 5), place(3, 1, PREFERRED, ALWAYS, AUTO, PREFERRED, AUTO, AUTO), "ALWAYS, the end, the turn before it; nothing is left for PREFERRED");
        assertEquals(Set.of(1, 3, 4, 5), place(4, 1, PREFERRED, ALWAYS, AUTO, PREFERRED, AUTO, AUTO), "then PREFERRED, from the latest backwards");
    }

    @Test
    void nothingIsChosenOnOrAfterTheFirstNever_withOrWithoutATrail() {
        assertEquals(Set.of(0, 1), place(4, 1, AUTO, AUTO, NEVER, AUTO, ALWAYS));
        assertEquals(Set.of(), place(4, 1, NEVER, AUTO, AUTO));
    }

    @Test
    void everyAlwaysTurnIsChosen_whateverTheAllowance() {
        assertEquals(Set.of(0, 1, 2), place(1, 1, ALWAYS, ALWAYS, ALWAYS, AUTO), "the allowance bounds what the placement adds, never what the author insisted on");
    }

    @Test
    void aConversationWhoseTurnsAreAllUnlikelyChoosesNothing() {
        assertEquals(Set.of(), place(4, 1, UNLIKELY, UNLIKELY, UNLIKELY));
    }
}
