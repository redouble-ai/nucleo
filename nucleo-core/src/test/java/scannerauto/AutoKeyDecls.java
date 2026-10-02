/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package scannerauto;

import ai.redouble.nucleo.prompt.*;

/**
 * Scanner fixture for the auto-derived key (an empty annotation {@code value()} derives
 * {@code <declaring-class-fqn>.<member-name>}) and for the instance-member skip (an
 * annotated instance field belongs to the thinker runtime, not the scanner).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
public final class AutoKeyDecls {
    @StaticPrompt
    public static final String GREETING = "hello auto";

    @StaticPrompt
    public final String instanceNote = "skipped by the scanner";
}
