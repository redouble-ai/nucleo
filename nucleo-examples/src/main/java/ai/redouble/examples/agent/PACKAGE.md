# Package: ai.redouble.examples.agent

An agent is a model working in a loop with tools. It reads the task, decides which tool to
call, reads what the tool returned, and goes on calling tools until it can answer. The
order of the work is the model's decision: nobody wrote "look up A-1002, then A-1005". That
is what an agent is for, work whose steps depend on what the question says and on what the
first lookups return.

In Nucleo an agent is called a thinker. This page writes one that answers a customer's
questions about orders, using the order-status tool from
[Your first tool](../tool/PACKAGE.md), unchanged.

## The agent

<!-- sample: OrderAgent.java#agent -->
```java
public class OrderAgent extends SingleObjectiveThinker<OrderQuestion, OrderAnswer> {
    public OrderAgent(Identifiable parent) {
        super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        setAnswerHandler(new PojoResponseHandler<>(OrderAnswer.class));
    }

    @StaticPrompt
    @Override
    protected String getSystemPromptText() {
        return """
            You answer a customer's question about their orders. Look up every order the
            question is about with order_status before you answer, and state each status as
            the lookup returned it.
            """;
    }

    @Override
    protected List<Class<? extends Tool>> declareDefaultTools() {
        return List.of(OrderStatusTool.class);
    }
}
```

Reading it from the top:

**One objective.** A `SingleObjectiveThinker` works toward one final answer. On each turn
the model either calls a tool or gives that answer, and the loop ends when it answers. Its
type parameters are the question it takes, `OrderQuestion`, and the answer it gives,
`OrderAnswer`. `OrderQuestion` extends `ThinkerInput` and carries the customer's words as
its query.

**How much model.** `ThinkerDeclaration` states what the agent needs from the model that
serves it. `Grade.SMALL` is the rung of model capability, as in
[Hello, model](../hello/PACKAGE.md). `OutputSize.COMPACT` says how long a single turn can
be: a tool call with its arguments, or an answer with a handful of fields. The runtime
turns it into a number of output tokens for whichever model serves the grade. Both are
required, so an agent without them does not compile.

**The instructions.** `getSystemPromptText()` is the system prompt, the standing
instructions the model reads before the question. `@StaticPrompt` registers the text under a
key derived from the class name, so a deployment can replace the wording without changing
the class, and promises the text never varies, so Nucleo prepares it once.

**The tools.** `declareDefaultTools()` lists the tools the model may call, here only
`OrderStatusTool`. The model is shown each tool's name, its description and the form its
input takes, all from the tool class itself, and that is how it knows `order_status` looks
up one order by its number.

**The answer.** `setAnswerHandler(new PojoResponseHandler<>(OrderAnswer.class))` tells the
agent to read its final answer into an `OrderAnswer`.

## The answer

<!-- sample: OrderAnswer.java#answer -->
```java
public class OrderAnswer extends ThinkerOutput<SimpleReasoning> {
    @LLMRequired
    @LLMDescription("The reply to the customer, stating each order's status as the lookup returned it")
    private String reply;
    @LLMRequired
    @LLMDescription("The numbers of the orders looked up to write the reply")
    private List<String> ordersLookedUp;
```

The answer is an ordinary Java class, and its fields are what the program wants back. The
model is shown the fields: `@LLMDescription` tells it what belongs in each, and
`@LLMRequired` says it must fill it. A reply that does not fit, or leaves a required field
empty, goes back to the model to be corrected before your code sees anything, so
`getReply()` and `getOrdersLookedUp()` hold values, never text to pick apart.

An agent's answer extends `ThinkerOutput`, which adds the model's account of how it reached
the answer, in the shape the type parameter names: `SimpleReasoning` is one thought.
[Answers as Java objects](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/schema/PACKAGE.md)
covers answer classes in full.

## Asking it

<!-- sample: FirstAgent.java#ask -->
```java
JobDispatcher dispatcher = JobDispatcher.getInstance();
dispatcher.start();
try {
    OrderAgent agent = new OrderAgent(Job.workflow("you", "first-agent"));
    agent.setInput(new OrderQuestion("Where are my orders A-1002 and A-1005?"));
    OrderAnswer answer = dispatcher.submit(agent).get();
    System.out.println(answer.getReply());
    System.out.println("looked up: " + answer.getOrdersLookedUp());
}
finally {
    dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
}
```

An agent is submitted to the dispatcher exactly like the tool was, because in Nucleo an
agent is a tool: it has a typed input and a typed output, and another agent could be given
it to call. Asked "Where are my orders A-1002 and A-1005?", it looks up both orders and
prints its reply and the two numbers it looked up.

## When the order number is wrong

Customers write order numbers the way they remember them. Asked "What is happening with my
order 1002?", the model may first call the tool with `1002`. The tool refuses it with
`InvalidInputException`, whose message says an order number is the letter A, a dash and
four digits. Nucleo hands that refusal back to the model as the result of its call, marked
as a mistake it can correct, and the model calls again with `A-1002` and answers.

Nothing in the agent handles the failure. A tool that throws one of Nucleo's typed
exceptions tells the model what went wrong and whether trying again can help, and the loop
does the rest.

## Next

[Your first doer](../doer/PACKAGE.md) puts the order of the work in Java, where the program
decides the steps and a model does only the part that needs one.
