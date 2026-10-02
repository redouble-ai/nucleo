/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.slf4j.*;

/**
 * Meta-tool that admits skills from the thinker's catalog into the current conversation, the
 * skill counterpart of {@link RequestToolsTool}. The model passes skill names; {@link ToolHub}
 * checks each against the reconciled catalog, runs the admission guardrails registered for the
 * thinker and skill, and attaches the admitted ones to the conversation, whose preamble carries
 * them on every later call. Rejected names come back with the reason so the model can adapt.
 *
 * <p>Resource-free ({@link AbstractDoer}): it coordinates admission guardrail jobs and touches
 * the conversation the thinker handed it through {@link ConversationAware}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
@ToolName("request_skill")
@ToolDescription(value = "Admit skills from the catalog. Pass skill names; each admitted skill's instructions ride every subsequent call in this conversation.", readOnly = true)
@ToolWeight(type = ToolType.IN_MEMORY, min = 1, max = 1)
public class RequestSkillTool extends AbstractDoer<RequestSkillInput, RequestSkillResult> implements ToolRegistryAware, ConversationAware {
    private static final Logger log = LoggerFactory.getLogger(RequestSkillTool.class);
    private ToolRegistry toolRegistry;
    private Thinker<?, ?> thinker;
    private ConversationContext conversation;

    public RequestSkillTool(Identifiable parent) {
        super(parent, "request-skill");
    }

    @Override
    public void setToolRegistry(ToolRegistry registry) {
        this.toolRegistry = registry;
    }

    @Override
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    @Override
    public void setThinker(Thinker<?, ?> thinker) {
        this.thinker = thinker;
    }

    @Override
    public Thinker<?, ?> getThinker() {
        return thinker;
    }

    @Override
    public void setConversation(ConversationContext conversation) {
        this.conversation = conversation;
    }

    @Override
    public ConversationContext getConversation() {
        return conversation;
    }

    @Override
    public RequestSkillResult execute(JobContext<RequestSkillResult> context) throws LLMReadableCheckedException {
        RequestSkillInput input = getInput();
        if (input.getSkillNames() == null || input.getSkillNames().isEmpty()) {
            throw new InvalidInputException("skillNames", null, "At least one skill name is required");
        }
        if (thinker == null || conversation == null) {
            throw new SystemException("RequestSkillTool",
                    "request_skill runs only as a tool of a thinker, which hands it the thinker and the conversation before submission", null);
        }
        RequestSkillResult result = ToolHub.getInstance().requestSkills(thinker, input.getSkillNames(), conversation);
        log.info("ToolHub: request_skill result for {} - admitted: {}{}",
                thinker.getClass().getSimpleName(),
                result.getAdmitted(),
                (result.getRejected() != null && !result.getRejected().isEmpty()
                    ? ", rejected: " + result.getRejected().stream().map(RequestSkillResult.RejectedSkill::getName).toList()
                    : ""));
        return result;
    }
}
