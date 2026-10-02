# Package: ai.redouble.examples.mcpwrap

The generic MCP call of [the previous example](../mcpclient/PACKAGE.md) sends untyped JSON
and gets text back. For a remote tool your workflows depend on, wrap it once as a tool of
your own, with the input and output classes fixed in your code:

<!-- sample: RemoteOrderStatusTool.java#wrapper -->
```java
@ToolName("order_status")
@ToolDescription(value = "Look up one order by its number: its status, its customer, the date it was promised for", readOnly = true)
public class RemoteOrderStatusTool extends AbstractTool<OrderNumber, OrderStatus> {
    public RemoteOrderStatusTool(Identifiable parent) {
        super(parent);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements requirements = new JobRequirements();
        requirements.setRequiresTransaction(false);
        requirements.setReadOnly(true);
        // The call travels over HTTP, so it takes a place on the shared HTTP pool
        requirements.setRequiresHttpConnection(true);
        return requirements;
    }

    @Override
    public OrderStatus execute(JobResources resources, JobContext<OrderStatus> context) throws LLMReadableCheckedException {
        try {
            ObjectNode arguments = NucleoJsonSerializer.createObjectNode();
            arguments.put("order_number", getInput().getOrderNumber());
            MCPToolResult result = OrdersServer.client().callTool("order_status", arguments);
            return NucleoJsonSerializer.parse(result.getTextContent(), OrderStatus.class);
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }
}
```

The class is a tool like [Your first tool](../tool/PACKAGE.md), with the same
`OrderNumber` in and `OrderStatus` out; only its body reaches over the wire. `OrdersServer`
holds the application's one connection, opened at startup and closed at shutdown, so the
tool keeps the `(Identifiable parent)` constructor every tool has and an agent creates it
like any other. Start the server example's `OrdersOverMcp` first, as for the previous page:

<!-- sample: WrapMcp.java#call -->
```java
JobDispatcher dispatcher = JobDispatcher.getInstance();
dispatcher.start();
OrdersServer.connect("http://localhost:8901/mcp");
try {
    RemoteOrderStatusTool tool = new RemoteOrderStatusTool(Job.workflow("you", "mcp-wrap"));
    tool.setInput(new OrderNumber("A-1002"));
    OrderStatus order = dispatcher.submit(tool).get();
    System.out.println(order);
}
finally {
    OrdersServer.close();
    dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
}
```

## Why this is the right way for serious work

For a remote tool your workflows depend on, the typed wrapper earns its thirty lines
several times over:

- **The contract is yours.** The generic path shows the model whatever the server publishes
  today: rename a field there and your agents quietly start seeing, and guessing at, a
  different tool. The wrapper pins what the model sees to your classes and your
  descriptions; when the server changes shape, the parse in `execute` fails loudly, in one
  place, with a typed seam to fix.
- **Scopes and guardrails can judge it.** The bindings of
  [An agent that stays inside its case](../scope/PACKAGE.md) work by reading typed fields
  off the input; a claim carried inside a generic JSON blob is invisible to them. Wrapped,
  the input can implement the same markers as any local tool, and a case-bound agent's
  remote calls are held to its case like the rest.
- **The declarations hold.** `readOnly = true` is yours to assert, so a read-only agent may
  keep the tool; a generic MCP tool is never read-only, because nothing can vouch for it.
  The requirements say what the call holds, and the result can carry your artifact types
  onward instead of text.
- **It is testable.** A test hands `OrdersServer` a fake and the tool runs without a
  network, like any other tool.

The generic path stays the right one for what it is for: browsing, one-off calls, an agent
that works across whatever servers it is pointed at.

## Next

The examples end here, and
[Tools, thinkers and doers](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/tools/PACKAGE.md)
begins the part on writing an agent. Every example ran on
[the job runtime](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/PACKAGE.md).
