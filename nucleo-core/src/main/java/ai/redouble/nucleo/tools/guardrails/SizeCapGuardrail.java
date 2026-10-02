/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.guardrails;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.prompt.*;

/**
 * Rejects Prompts whose rendered text exceeds a configurable character limit. Ships as a
 * baseline guardrail; apps wire it via {@code Prompts.addBaselineGuardrail} or per-key
 * via {@code Prompts.addGuardrail}.
 *
 * <p>{@code Prompts.produce} dispatches it as a one-shot {@link Job} and blocks on the
 * verdict. Size-cap is a pure content check with no resource needs, so this guardrail
 * declares an empty resource footprint and can run safely inside resource-holding callers.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
public class SizeCapGuardrail extends AbstractContentGuardrail<Prompt> {
    private final int maxChars;

    public SizeCapGuardrail(Identifiable parent, int maxChars) {
        super(parent);
        this.maxChars = maxChars;
    }

    @Override
    public Direction direction() {
        return Direction.INPUT;
    }

    @Override
    public Class<Prompt> targetType() {
        return Prompt.class;
    }

    @Override
    public void validate(Prompt target) throws GuardrailException {
        if (target == null) {
            throw new GuardrailException("Prompt is null");
        }
        String text = target.content() == null ? "" : target.content().asText();
        if (text.length() > maxChars) {
            throw new GuardrailException("Prompt '" + target.key() + "' exceeds size cap: " +
                text.length() + " chars > " + maxChars);
        }
    }

    public int getMaxChars() {
        return maxChars;
    }
}
