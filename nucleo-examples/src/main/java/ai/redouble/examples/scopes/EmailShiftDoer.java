/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scopes;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * The shift: one customer's case, worked on the email channel. Real work overlaps several
 * axes at once, and two unrelated bindings on one flow means implementing both markers;
 * that leaves the class with two default {@code scope()} methods, so the compiler forces
 * the composite override - one flow answering for both axes.
 *
 * <p>Partway through, the shift escalates one disputed order by spawning
 * {@link DisputeDoer}: from that point the case axis is narrowed to one order for
 * everything the escalation does, while the shift's own bindings ride into it through the
 * runtime and keep holding.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
// region shift
@ToolName("email_shift")
@ToolDescription("Work one customer's case on the email channel")
public class EmailShiftDoer extends AbstractDoer<String, List<String>> implements CaseScoped, ChannelScoped {
    private final String customerId;

    public EmailShiftDoer(Identifiable parent, String customerId) {
        super(parent);
        this.customerId = customerId;
    }

    @Override
    public String getCustomerId() {
        return customerId;
    }

    @Override
    public String getChannel() {
        return "email";
    }

    @Override
    public Scope scope() {
        return new CompositeScope(CaseScoped.super.scope(), ChannelScoped.super.scope());
    }
    // endregion

    @Override
    public List<String> execute(JobContext<List<String>> context) throws LLMReadableCheckedException {
        List<String> outcomes = new ArrayList<>();
        // Before the narrowing: any order of the case is workable on this shift
        outcomes.add(attempt(new FileNote(customerId, "A-1005", "email", "address confirmed with the customer")));
        // From here the dispute narrows the case to one order; the shift's bindings ride along
        try {
            outcomes.addAll(submitInStep(new DisputeDoer(this, customerId, "A-1002")).get());
        }
        catch (ExecutionException | InterruptedException e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
        return outcomes;
    }

    private String attempt(FileNote note) throws LLMReadableCheckedException {
        FileNoteTool tool = new FileNoteTool(this);
        tool.setInput(note);
        try {
            return submitInStep(tool).get();
        }
        catch (ExecutionException e) {
            if (e.getCause() instanceof GuardrailException refused) {
                return "refused: " + refused.getMessage();
            }
            throw LLMReadableCheckedException.unwrap(e);
        }
        catch (InterruptedException e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }
}
