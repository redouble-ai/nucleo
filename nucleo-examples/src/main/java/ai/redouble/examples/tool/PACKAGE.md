# Package: ai.redouble.examples.tool

A model on its own can only read text and write text. It cannot look up an order, check a
balance or file a ticket. For that it needs tools: pieces of your code that it may ask to
have run. The model never touches your systems. It says "run this tool with these
arguments", Nucleo runs your code, and the model reads what came back.

In Nucleo a tool is a Java class that does one job and describes itself well enough for a
model to know when to use it. It is also an ordinary piece of your code: your own Java can
call it exactly the way a model does. This page writes one tool, finding an order by its
number, and calls it from code. The next page hands the same class to a model.

## The tool

<!-- sample: OrderStatusTool.java#tool -->
```java
@ToolName("order_status")
@ToolDescription(value = "Look up one order by its number: its status, its customer, the date it was promised for", readOnly = true)
public class OrderStatusTool extends AbstractTool<OrderNumber, OrderStatus> {
    public OrderStatusTool(Identifiable parent) {
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
    public OrderStatus execute(JobResources resources, JobContext<OrderStatus> context) throws LLMReadableCheckedException {
        String number = getInput().getOrderNumber();
        if (!Orders.NUMBER.matcher(number).matches()) {
            throw new InvalidInputException("orderNumber", number, "an order number is the letter A, a dash and four digits, like A-1042");
        }
        OrderStatus order = Orders.find(number);
        if (order == null) {
            throw new ResourceNotFoundException("order", number);
        }
        return order;
    }
}
```

Reading it from the top:

**The label.** A model chooses a tool the way a person chooses a button: by reading its
label. `@ToolName` is the name the model calls it by, and `@ToolDescription` tells the model
what the tool is for, so it is worth writing as carefully as a method's documentation.
`readOnly = true` promises that the tool changes nothing, which lets Nucleo offer it to
agents that are only allowed to look.

**What goes in and what comes out.** `AbstractTool<OrderNumber, OrderStatus>` says the tool
takes an `OrderNumber` and returns an `OrderStatus`, both plain Java beans. From the input
class, Nucleo builds the form a model fills in when it calls the tool; the output comes back
to your code as the object itself, and to a model as text it can read.

**What it needs while it runs.** Some tools need a database connection or a transaction.
`getRequirements()` is where a tool asks for them, and Nucleo hands them over when the tool
starts and takes them back when it ends, so the tool never manages them itself. This one
needs nothing.

**The work.** `execute` is your code, and here it is a map lookup. The orders come from
`Orders`, five orders held in memory, standing in for your database. When the tool cannot do
its job it throws, and the exception says whose problem it is. `InvalidInputException`
means the caller asked the wrong way, and its message says how to ask right, so a model that
sent a bad number can correct itself and call again. `ResourceNotFoundException` means the
order does not exist.

## Calling it from your code

<!-- sample: FirstTool.java#submit -->
```java
JobDispatcher dispatcher = JobDispatcher.getInstance();
dispatcher.start();
try {
    OrderStatusTool tool = new OrderStatusTool(Job.workflow("you", "first-tool"));
    tool.setInput(new OrderNumber("A-1002"));
    OrderStatus order = dispatcher.submit(tool).get();
    System.out.println(order);
}
finally {
    dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
}
```

Everything Nucleo runs, whether a tool, an agent or a single call to a model, is a job, and
`JobDispatcher` is what runs jobs. You create the job, give it its input and submit it; the
dispatcher runs it on its own virtual thread and gives you back a handle, and `get()` waits
for the result. A model calling this tool goes through the same dispatcher, which is why the
tool behaves the same whoever called it.

`Job.workflow("you", "first-tool")` says who the work is for and what it is. Everything done
under one workflow is recorded, costed and limited together. In a Spring Boot or Quarkus
application, starting and stopping the dispatcher is done for you.

## Next

[Your first agent](../agent/PACKAGE.md) gives this tool to a model and lets the model
decide when to call it.
