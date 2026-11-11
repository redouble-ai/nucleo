/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm;

import ai.redouble.nucleo.harness.conversation.*;

import java.util.*;

/**
 * The default choice of the turns that carry a prompt-cache breakpoint, for a provider whose
 * cache is written by breakpoints: a breakpoint stores the request from its first byte
 * through the block that carries it, so the choice is where stored prefixes end. Each turn
 * states its {@link Cacheability}; a provider's client says which of its turns can carry a
 * breakpoint and how many the provider takes, and marks the turns this class returns.
 * A client that wants another placement does not call it.
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-11)
 */
public final class CacheBreakpointPlacement {

    private CacheBreakpointPlacement() {
    }

    /**
     * The turns that carry a breakpoint, as indexes into {@code turns}. The allowance is
     * spent in this order, and the spending stops when nothing is left:
     * <ol>
     *   <li>every {@code ALWAYS} turn, whatever their number: when that is more than the
     *       provider takes, what the provider does with the request is the caller's to see;</li>
     *   <li>the end of the conversation, the last turn a stored prefix may end on, so each
     *       request reads what the previous one sent and writes only what was added since;</li>
     *   <li>the {@code trail} turns before the end that a stored prefix may end on, for a
     *       provider that finds an earlier entry only where the request itself carries a
     *       breakpoint: the end of the previous request is then marked again on this one;</li>
     *   <li>{@code PREFERRED} turns, from the latest backwards;</li>
     *   <li>the first turn of the conversation, the original request.</li>
     * </ol>
     * Nothing is chosen on or after the first {@code NEVER} turn, since a prefix ending there
     * would contain it. A turn that cannot carry a breakpoint is passed over.
     *
     * @param turns the prepared turns, in order
     * @param carriers per turn, whether the provider lets it carry a breakpoint
     * @param allowance the breakpoints left for turns on this request
     * @param trail how many turns before the end are marked with it
     */
    public static Set<Integer> turns(List<ProcessedMessageData> turns, List<Boolean> carriers, int allowance, int trail) {
        int end = turns.size();
        for (int i = 0; i < turns.size(); i++) {
            if (turns.get(i).cacheability() == Cacheability.NEVER) {
                end = i;
                break;
            }
        }
        Set<Integer> chosen = new TreeSet<>();
        for (int i = 0; i < end; i++) {
            if (turns.get(i).cacheability() == Cacheability.ALWAYS && carriers.get(i)) {
                chosen.add(i);
            }
        }
        int last = -1;
        for (int i = end - 1; i >= 0 && chosen.size() < allowance; i--) {
            if (turns.get(i).cacheability().mayEndPrefix() && carriers.get(i)) {
                chosen.add(i);
                last = i;
                break;
            }
        }
        int trailed = 0;
        for (int i = last - 1; i >= 0 && trailed < trail && chosen.size() < allowance; i--) {
            if (turns.get(i).cacheability().mayEndPrefix() && carriers.get(i)) {
                chosen.add(i);
                trailed++;
            }
        }
        for (int i = end - 1; i >= 0 && chosen.size() < allowance; i--) {
            if (turns.get(i).cacheability() == Cacheability.PREFERRED && carriers.get(i)) {
                chosen.add(i);
            }
        }
        if (end > 0 && chosen.size() < allowance && turns.getFirst().cacheability().mayEndPrefix() && carriers.getFirst()) {
            chosen.add(0);
        }
        return chosen;
    }
}
