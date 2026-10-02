/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.guardrails;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.prompt.*;

import java.util.regex.*;

/**
 * Rejects Prompts whose content contains framework-internal artifact markers of the form
 * {@code «artifact:...»}. Such markers are produced by
 * {@code NucleoJsonSerializer.writeSummarizedWithRefs} and must never appear in
 * developer-authored prompt text - if they do, something has smuggled registry content
 * into a registered prompt, which is a data-exfiltration smell.
 *
 * <p>Baseline guardrail; wire via {@code Prompts.addBaselineGuardrail}. Pure regex check,
 * no resource footprint, safe inside resource-holding callers.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
public class ExfiltrationMarkerGuardrail extends AbstractContentGuardrail<Prompt> {
    private static final Pattern MARKER = Pattern.compile("«artifact:[^»]*»");

    public ExfiltrationMarkerGuardrail(Identifiable parent) {
        super(parent);
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
        if (target == null || target.content() == null) {
            return;
        }
        String text = target.content().asText();
        Matcher m = MARKER.matcher(text);
        if (m.find()) {
            throw new GuardrailException("Prompt '" + target.key() +
                "' contains artifact exfiltration marker: " + m.group());
        }
    }
}
