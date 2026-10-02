# Package: ai.redouble.examples.mcpclient

The tools an agent needs are not always in its own process. Thousands of MCP servers offer
tools over one protocol: search engines, issue trackers, databases, and applications like
the one in [Your tools over MCP](../mcpserver/PACKAGE.md), which this example connects to.
Nucleo's generic MCP client works against any of them without a line of server-specific
code: name the endpoint, attach, and every tool the server grants is ready to hand to an
agent or to call from code.

<!-- sample: ListAndCall.java#attach -->
```java
HTTPMCPEndpoint endpoint = new HTTPMCPEndpoint();
endpoint.setUrl("http://localhost:8901/mcp");
endpoint.setFlavor(HTTPMCPEndpoint.Flavor.STREAMABLE_HTTP);

JobDispatcher dispatcher = JobDispatcher.getInstance();
dispatcher.start();
MCPConnector connector = MCPConnectorRegistry.attach(new MCPHandle("orders"), endpoint);
try {
    for (MCPToolProvider provider : connector.providers()) {
        System.out.println(provider.name() + ": " + provider.description());
    }
    MCPToolProvider orderStatus = connector.providers().get(0);
    ObjectNode arguments = NucleoJsonSerializer.createObjectNode();
    arguments.put("order_number", "A-1002");
    MCPToolInput input = new MCPToolInput();
    input.setArguments(arguments);
    GenericMCPToolAdapter call = (GenericMCPToolAdapter) orderStatus.create(Job.workflow("you", "mcp-call"));
    call.setInput(input);
    MCPToolResult result = dispatcher.submit(call).get();
    System.out.println(result.getTextContent());
}
finally {
    MCPConnectorRegistry.detach(connector);
    dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
}
```

Reading it from the top:

**The endpoint** says where the server is and how it speaks: a URL for a server running as
a service, or the command that starts one as a subprocess (`STDIOMCPEndpoint`). A server
behind a key takes it on the endpoint's credential; this one is open.

**Attaching** connects, fetches the server's tool list once, and gives back one
`MCPToolProvider` per tool the server grants. Each is ready to hand to an agent -
`thinker.addTool(provider)` - and the model then sees it beside the agent's own tools,
named `orders_order_status`: the handle from `attach`, an underscore, and the tool's own
name, so two servers that both offer `search` stay apart.

**Calling from code** goes through the same door as everything else: the provider creates
the adapter job, the arguments travel as raw JSON in the server's own schema (snake_case
here, because that is what the server published), and the dispatcher admits the call
against the endpoint's limits. The result comes back as `MCPToolResult`, its text content
whatever the server chose to send.

Start the server example's `OrdersOverMcp` first; run against it, this main prints the one
granted tool, then the order as JSON.

## The raw JSON is the point to notice

Nothing above knows what an order is. The arguments are an untyped `ObjectNode` spelled to
match a schema this code never sees, and the result is text to pick apart. For an agent
that browses many servers, that generality is exactly right. For a tool your workflows
depend on, the next example wraps it properly.

## Next

[An MCP tool wrapped as your own](../mcpwrap/PACKAGE.md).
