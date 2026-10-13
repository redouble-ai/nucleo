/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

import ai.redouble.nucleo.harness.models.*;

import java.util.*;
import java.util.function.*;

/**
 * The ledger with no provider knowledge in it: the counts as given. The core default
 * estimator produces it, and so does a client whose provider reports plain counts, an
 * embeddings or a decision call among them.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
public class DefaultTokenLedger extends AbstractTokenLedger {

    /** A reported usage with the counts as the provider stated them. */
    public DefaultTokenLedger(ModelSpec spec, Integer inputTokens, Integer cacheReadTokens, Integer cacheWriteTokens, Integer outputTokens) {
        super(spec, inputTokens, cacheReadTokens, cacheWriteTokens, outputTokens);
    }

    /** An estimate: the input by part, and the output reserve resolved when first read. */
    public DefaultTokenLedger(ModelSpec spec, Map<ContextPart, Integer> inputByPart, IntSupplier outputReserve) {
        super(spec, inputByPart, outputReserve);
    }
}
