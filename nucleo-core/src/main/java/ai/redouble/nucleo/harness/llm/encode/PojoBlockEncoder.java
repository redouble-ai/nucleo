/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.llm.encode;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.errors.*;

/**
 * {@link PojoBlock} encoder: an invariant guard. A PojoBlock must be lowered to a JsonBlock by
 * ContentProcessor before reaching a client; one arriving here is a programming error, surfaced
 * loudly rather than silently dropped. Uncorrectable - the model cannot fix an upstream lowering bug.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-18)
 */
public class PojoBlockEncoder<B> extends BlockEncoder<B> {
    @Override
    public B encode(ContentBlock block) {
        throw new UncorrectableRuntimeLLMException("PojoBlock must be processed by ContentProcessor before reaching the client: "
                + ((PojoBlock) block).pojo().getClass().getSimpleName());
    }
}
