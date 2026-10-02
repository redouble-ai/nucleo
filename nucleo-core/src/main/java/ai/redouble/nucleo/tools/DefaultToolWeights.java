/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import ai.redouble.nucleo.tools.registry.*;

import java.lang.annotation.*;

/**
 * Default {@link ToolWeight} instances for unannotated tools.
 * {@link ClassToolProvider} answers with these from {@code weight()} when a tool
 * class does not have an explicit {@code @ToolWeight} annotation: the thinker
 * default for tools that are thinkers, the API-call default for everything else.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-02-21)
 */
public final class DefaultToolWeights {
    public static final ToolWeight THINKER_DEFAULT = new ToolWeight() {
        @Override
        public ToolType type() { return ToolType.THINKER; }
        @Override
        public int level() { return 0; }
        @Override
        public int min() { return 20; }
        @Override
        public int max() { return 80; }
        @Override
        public Class<? extends Annotation> annotationType() { return ToolWeight.class; }
    };
    public static final ToolWeight API_CALL_DEFAULT = new ToolWeight() {
        @Override
        public ToolType type() { return ToolType.API_CALL; }
        @Override
        public int level() { return 0; }
        @Override
        public int min() { return 10; }
        @Override
        public int max() { return 10; }
        @Override
        public Class<? extends Annotation> annotationType() { return ToolWeight.class; }
    };
    private DefaultToolWeights() {}
}
