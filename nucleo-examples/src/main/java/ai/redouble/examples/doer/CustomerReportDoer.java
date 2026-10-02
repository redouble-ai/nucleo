/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.examples.doer;

import ai.redouble.examples.tool.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.builtin.*;

import java.util.*;

/**
 * Orchestration written in Java. Where an agent leaves the order of the work to the model,
 * a doer states it in code: look up every order of one customer in parallel, then ask a
 * model one question over what came back. Use a doer where the steps are known, and an
 * agent where the model should decide them.
 *
 * <p>A doer is a tool: the same {@code @ToolName} and typed input and output, so an agent
 * could call it and code submits it like any job. Each lookup is a new tool created with
 * this doer as its parent, so it appears under the doer in the record of the run.
 * {@code nextStep()} plus {@code submitInCurrentStep} fan the lookups out into one step,
 * all running at once on virtual threads of their own; {@code submitInStep} gives the model
 * call a step after them. The step numbers are how the record shows what ran side by side.
 *
 * <p>A doer holds nothing while it waits: its {@code execute} has no resources parameter,
 * and each job it runs asks for what it needs and holds it only while it runs. A hundred
 * doers waiting on their lookups hold no connections between them.
 * {@code LLMReadableCheckedException.unwrap} passes the runtime's retry signals through and
 * reports anything else as a failure the caller can read.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
// region doer
@ToolName("customer_report")
@ToolDescription(value = "Look up every order of one customer and answer a question over them", readOnly = true)
public class CustomerReportDoer extends AbstractDoer<CustomerQuestion, CustomerReport> {
    public CustomerReportDoer(Identifiable parent) {
        super(parent);
    }

    @Override
    public CustomerReport execute(JobContext<CustomerReport> context) throws LLMReadableCheckedException {
        try {
            // Fan out: one lookup per order, all in one step, running at once
            nextStep();
            List<JobHandle<OrderStatus>> lookups = new ArrayList<>();
            for (OrderStatus known : Orders.ofCustomer(getInput().getCustomerId())) {
                OrderStatusTool lookup = new OrderStatusTool(this);
                lookup.setInput(new OrderNumber(known.getOrderNumber()));
                lookups.add(submitInCurrentStep(lookup));
            }
            // Fan in
            List<OrderStatus> orders = new ArrayList<>();
            StringBuilder statuses = new StringBuilder();
            for (JobHandle<OrderStatus> lookup : lookups) {
                OrderStatus order = lookup.get();
                orders.add(order);
                statuses.append(order).append('\n');
            }
            // One model call over the result, the next step
            QuickLLMQuestionInput question = new QuickLLMQuestionInput();
            question.setQuestion(getInput().getQuestion());
            question.setContext(statuses.toString());
            question.setGrade(Grade.SMALL);
            QuickLLMQuestionTool ask = new QuickLLMQuestionTool(this);
            ask.setInput(question);
            CustomerReport report = new CustomerReport();
            report.setOrders(orders);
            report.setAnswer(submitInStep(ask).get().getAnswer());
            return report;
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }
}
// endregion
