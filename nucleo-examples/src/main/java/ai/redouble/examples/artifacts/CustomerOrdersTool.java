/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.artifacts;

import ai.redouble.examples.tool.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;

import java.util.*;

/**
 * Lists one customer's orders as artifacts. Nothing in the tool is about artifacts beyond
 * returning them: the runtime registers each one as the result reaches the model and shows
 * the model its reference beside its fields.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
@ToolName("customer_orders")
@ToolDescription(value = "List every order of one customer", readOnly = true)
public class CustomerOrdersTool extends AbstractTool<CustomerId, CustomerOrders> {
    public CustomerOrdersTool(Identifiable parent) {
        super(parent);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements requirements = new JobRequirements();
        requirements.setRequiresTransaction(false);
        requirements.setReadOnly(true);
        return requirements;
    }

    @Override
    public CustomerOrders execute(JobResources resources, JobContext<CustomerOrders> context) throws LLMReadableCheckedException {
        List<OrderRecord> records = new ArrayList<>();
        for (OrderStatus order : Orders.ofCustomer(getInput().getCustomerId())) {
            OrderRecord record = new OrderRecord();
            record.setOrderNumber(order.getOrderNumber());
            record.setStatus(order.getStatus());
            record.setPromisedFor(order.getPromisedFor());
            record.setNote(order.getNote());
            records.add(record);
        }
        CustomerOrders orders = new CustomerOrders();
        orders.setOrders(records);
        return orders;
    }
}
