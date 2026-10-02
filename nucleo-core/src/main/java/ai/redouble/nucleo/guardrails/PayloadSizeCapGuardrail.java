/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.schema.*;

/**
 * Reference INPUT-direction {@link ContentGuardrail}: refuses any input whose serialized
 * form exceeds a character cap. The canonical example of a PARAMETERIZED guardrail -
 * declared as a constructed instance, cap in hand:
 * <pre>{@code
 * @Override
 * public List<ContentGuardrail<?>> declareContentGuardrails() {
 *     return List.of(new PayloadSizeCapGuardrail(this, 100_000));
 * }
 * }</pre>
 * Applies to any input ({@code Object}); a null input passes. The refusal names the
 * serialized length, the cap and the gated job's class, or {@code this tool} when the
 * guard runs outside dispatch enforcement and gates no job.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public class PayloadSizeCapGuardrail extends AbstractContentGuardrail<Object> {
    private final int maxChars;

    public PayloadSizeCapGuardrail(Identifiable parent, int maxChars) {
        super(parent);
        this.maxChars = maxChars;
    }

    @Override
    public Direction direction() {
        return Direction.INPUT;
    }

    @Override
    public Class<Object> targetType() {
        return Object.class;
    }

    @Override
    public void validate(Object target) throws GuardrailException {
        if (target == null) {
            return;
        }
        int length = NucleoJsonSerializer.write(target).length();
        if (length > maxChars) {
            throw new GuardrailException("Input of " + length + " characters exceeds the cap of "
                    + maxChars + " for " + describeGated());
        }
    }

    private String describeGated() {
        JobSnapshot snapshot = getGatedSnapshot();
        return snapshot != null ? snapshot.jobClass().getSimpleName() : "this tool";
    }
}
