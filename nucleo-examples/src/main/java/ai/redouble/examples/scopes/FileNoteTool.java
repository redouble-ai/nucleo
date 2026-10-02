/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.scopes;

import ai.redouble.examples.tool.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;

/**
 * Files a note on one order of one customer. The tool acts on exactly the fields the claim
 * carries, which is what makes the bindings mean something: it looks the order up among
 * the claimed customer's orders only.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-28)
 */
@ToolName("file_note")
@ToolDescription("File a note on one order of one customer, naming the channel it came from")
public class FileNoteTool extends AbstractTool<FileNote, String> {
    public FileNoteTool(Identifiable parent) {
        super(parent);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements requirements = new JobRequirements();
        requirements.setRequiresTransaction(false);
        return requirements;
    }

    @Override
    public String execute(JobResources resources, JobContext<String> context) throws LLMReadableCheckedException {
        FileNote note = getInput();
        for (OrderStatus order : Orders.ofCustomer(note.getCustomerId())) {
            if (order.getOrderNumber().equals(note.getOrderNumber())) {
                return "filed on " + note.getCustomerId() + "/" + note.getOrderNumber()
                        + " via " + note.getChannel() + ": " + note.getNote();
            }
        }
        throw new ResourceNotFoundException("order of " + note.getCustomerId(), note.getOrderNumber());
    }
}
