/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.sommelier;

import ai.redouble.nucleo.*;

/**
 * The knobs of the tool catalog lookup.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
public class SommelierSettings extends Settings {

    /**
     * The longest catalog a thinker is shown as a list. A thinker with this many tools on
     * offer or fewer sees every name and description in {@code request_tools}; one with
     * more sees none of them and has {@code find_tools} instead.
     */
    public volatile int listedTools = 30;

    /**
     * About how many tokens of tool pages {@link ModelDrivenSommelier} puts in one model call. A
     * catalog longer than this is read in parts of about this size.
     */
    public volatile int partTokens = 50_000;
}
