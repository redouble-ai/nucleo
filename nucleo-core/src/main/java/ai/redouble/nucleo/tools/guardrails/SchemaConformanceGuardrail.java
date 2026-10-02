/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.guardrails;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.prompt.skill.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.slf4j.*;

/**
 * Validates a {@link Skill}'s structural invariants before admission:
 * <ul>
 *   <li>{@code name} present and non-blank.</li>
 *   <li>{@code body} non-null.</li>
 *   <li>{@code suggestedTools} entries are known to {@link ToolHub#isKnownTool}.
 *       Unresolved names surface as a WARN log rather than a rejection, because
 *       Skills may be published before every suggested tool is registered in the running
 *       process.</li>
 * </ul>
 *
 * <p>Structural failures (missing name or body) throw {@link GuardrailException}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
public class SchemaConformanceGuardrail extends AbstractContentGuardrail<Skill> {
    private static final Logger log = LoggerFactory.getLogger(SchemaConformanceGuardrail.class);
    public SchemaConformanceGuardrail(Identifiable parent) {
        super(parent);
    }

    @Override
    public Direction direction() {
        return Direction.INPUT;
    }

    @Override
    public Class<Skill> targetType() {
        return Skill.class;
    }

    @Override
    public void validate(Skill target) throws GuardrailException {
        if (target == null) {
            throw new GuardrailException("Skill is null");
        }
        if (target.name() == null || target.name().isBlank()) {
            throw new GuardrailException("Skill.name is blank");
        }
        if (target.body() == null) {
            throw new GuardrailException("Skill '" + target.name() + "' has no body");
        }
        if (target.suggestedTools() != null) {
            for (String toolName : target.suggestedTools()) {
                if (!ToolHub.getInstance().isKnownTool(toolName)) {
                    log.warn("Skill '{}' suggests unknown tool: {}", target.name(), toolName);
                }
            }
        }
    }
}
