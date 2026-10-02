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
 * The escalation: work on one disputed order. It declares only the order binding, the
 * narrowing of the case ({@link OrderScope} extends {@link CaseScope}, which is what
 * relates the two axes); the case and channel bindings of whoever spawned it ride in
 * through the runtime and keep holding, so a note on the same customer's other order is
 * refused by the narrowing, a phone note by the inherited channel binding, and another
 * customer's order on the case axis itself.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
// region dispute
@ToolName("dispute")
@ToolDescription("Work one disputed order: file the notes of the dispute")
public class DisputeDoer extends AbstractDoer<String, List<String>> implements OrderScoped {
    private final String customerId;
    private final String orderNumber;

    public DisputeDoer(Identifiable parent, String customerId, String orderNumber) {
        super(parent);
        this.customerId = customerId;
        this.orderNumber = orderNumber;
    }

    @Override
    public String getCustomerId() {
        return customerId;
    }

    @Override
    public String getOrderNumber() {
        return orderNumber;
    }
    // endregion

    @Override
    public List<String> execute(JobContext<List<String>> context) throws LLMReadableCheckedException {
        List<String> outcomes = new ArrayList<>();
        // The disputed order itself: inside every binding
        outcomes.add(attempt(new FileNote(customerId, orderNumber, "email", "customer disputes the second charge")));
        // Another order of the same customer: the case allows it, the order binding refuses it
        outcomes.add(attempt(new FileNote(customerId, "A-1005", "email", "also mention the address problem")));
        // The disputed order, claimed for the phone channel: the inherited channel binding refuses it
        outcomes.add(attempt(new FileNote(customerId, orderNumber, "phone", "called the customer back")));
        // Another customer entirely: the case axis refuses it
        outcomes.add(attempt(new FileNote("C-200", "A-1003", "email", "unrelated case")));
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
