/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.deciding;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;

import java.time.*;

/**
 * One decision, as a job: a {@link DecisionRequest} in, the answers by question id out. The
 * leaf every decision in the runtime is, whichever code or orchestrator asks it: it declares
 * the decision seat, and so it goes through the whole door (resolution to the deployment's
 * decision model, pricing of its state against the entry's ceiling, admission on the
 * entry's account, the spend gates) and its call lands on the job's record with the
 * distributions the model produced. It holds the decision client for the length of one
 * round trip and nothing else.
 *
 * <p>Not a palette tool: it carries no tool name, so no thinker's model can call it. A
 * chat model asking a decision model is a design a caller makes deliberately, by wrapping
 * this in a named tool with a schema it authored.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public class DecisionCall extends AbstractTool<DecisionRequest, DecisionResponse> {
    /** A decision is one round trip of a few hundred milliseconds; a minute is a hung endpoint. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);
    private ModelSpec pinned;
    private ModelBinding binding;

    public DecisionCall(Identifiable parent) {
        super(parent, "decision");
        setTimeout(DEFAULT_TIMEOUT);
    }

    /** With the request in hand. */
    public DecisionCall(Identifiable parent, DecisionRequest request) {
        this(parent);
        setInput(request);
    }

    /** Names the exact decision entry, skipping the deployment's declaration; the gate still judges it. */
    public void pinModel(ModelSpec pinned) {
        this.pinned = pinned;
    }

    /**
     * One decision binding, priced from the request and read-only (a decision reads and
     * writes nothing of the deployment's). The request must be set first: the requirements
     * price it, so a call without one is refused here with an {@link IllegalStateException}.
     */
    @Override
    public JobRequirements getRequirements() {
        if (input == null) {
            throw new IllegalStateException("A decision call needs its request before its requirements are captured");
        }
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        // priced from the request as the wire will carry it, counted under the resolved entry's
        // tokenizer, so a state past the entry's ceiling fails at resolution, not on the wire
        String text = SystemOneWire.encode(pinned != null ? pinned.getWireModelId() : null, input);
        binding = pinned != null ? req.requireDecisionForText(pinned, text) : req.requireDecisionForText(text);
        return req;
    }

    @Override
    public DecisionResponse execute(JobResources resources, JobContext<DecisionResponse> context) throws LLMReadableCheckedException {
        try {
            return resources.getDecisionClient(binding.getModel()).decide(input);
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }
}
