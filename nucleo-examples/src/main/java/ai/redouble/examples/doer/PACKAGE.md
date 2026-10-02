# Package: ai.redouble.examples.doer

[Your first agent](../agent/PACKAGE.md) left the order of the work to the model. Often you
already know the steps: look up every order of a customer, then ask one question over all of
them. Handing that to a model costs a model turn per decision and gives up control of
something the program could simply state.

A doer is the Java answer. It is an orchestrator, a job whose work is running other jobs,
and its steps are written as ordinary Java code. It can run tools and agents, in sequence or
all at once, and it can use a model where a step needs one. Use a doer where the order of
the work is known, and an agent where the model should decide it.

## The doer

This one looks up every order of one customer in parallel, then asks a model a question over
what came back.

<!-- sample: CustomerReportDoer.java#doer -->
```java
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
```

Reading it from the top:

**A doer is a tool.** It carries the same `@ToolName` and `@ToolDescription` as the tool in
[Your first tool](../tool/PACKAGE.md), and `AbstractDoer<CustomerQuestion, CustomerReport>`
names its input and output the same way. An agent given `customer_report` could call it,
and your own code submits it to the dispatcher like any other job. Its input,
`CustomerQuestion`, is a customer id and a question; its output, `CustomerReport`, is the
orders exactly as the tool returned them and the model's answer.

**Its work is other jobs.** Each lookup is a new `OrderStatusTool` created with the doer as
its parent, `new OrderStatusTool(this)`, so the lookups belong to the doer's part of the
workflow and appear under it in the record of the run.

**Fan out, then fan in.** `nextStep()` starts a new step, and `submitInCurrentStep` submits
each lookup into it, so all of them run at once, each on a virtual thread of its own. The
second loop calls `get()` on each handle, waiting for its result. The step numbers are how
the record of the run shows which jobs ran side by side and which came after.

**One model call over the result.** The question goes to `QuickLLMQuestionTool`, the tool
from [Hello, model](../hello/PACKAGE.md), with the looked-up orders as the text it is about.
`submitInStep` gives it a step of its own, after the lookups.

**Failures pass through.** `LLMReadableCheckedException.unwrap` rethrows a failure of a
child the way the caller can read it: one of Nucleo's typed exceptions as itself, and the
runtime's own retry signals as themselves, so the dispatcher can still retry. Anything else
becomes a failure the caller can read.

## A doer holds nothing while it waits

The `execute` of a doer has no `JobResources` parameter, unlike the tool's: a doer gets no
database connection and makes no model call of its own. Each job it runs asks for what it
needs and holds it only while it runs. A lookup that needed a database connection would get
one for the length of the lookup, and a hundred doers waiting on their lookups hold no
connections between them.

## Next

[Data the model cannot alter](../artifacts/PACKAGE.md) is the first of the examples of what
the runtime keeps under the application's control.
