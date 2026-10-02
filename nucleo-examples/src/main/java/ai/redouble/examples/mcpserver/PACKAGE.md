# Package: ai.redouble.examples.mcpserver

The tools of this application are useful beyond it: a coding assistant, another team's
agent, an operations chatbot could all use the order lookup. MCP, the Model Context
Protocol, is the standard way to offer them - a small HTTP protocol under which a client
lists the tools a server grants it and calls them. This example serves the order-status
tool from [Your first tool](../tool/PACKAGE.md) to MCP clients, from a Spring Boot
application. The next two examples connect to it.

## Marking the tool

The tool class gains one annotation, `@MCP`, and changes in no other way. The annotation
makes the tool offerable; whether any caller actually sees it is decided by the grants
below, and local agents keep using the tool exactly as before.

## The application

`OrdersOverMcp` is a plain Spring Boot main. In a real application the Nucleo starter
starts the runtime with the application and binds credentials from properties
([Running inside Spring Boot](../../../../../../../../nucleo-spring-boot-starter/src/main/java/ai/redouble/nucleo/spring/PACKAGE.md));
this module keeps the starter off its classpath, because its other examples are plain
mains, and a `NucleoRuntime` bean in the config does the starter's one job here. The MCP
endpoint is three beans:

<!-- sample: McpServerConfig.java#server -->
```java
@Bean
public HttpServletStatelessServerTransport mcpTransport() {
    return HttpServletStatelessServerTransport.builder()
            .messageEndpoint("/mcp")
            // Refuses duplicate keys and the other things a lenient JSON reader lets through
            .jsonMapper(McpBoundaryJson.strictMapper())
            // A request carrying an Origin outside this list is refused: a browser page
            // cannot call the server. The example's own clients send no Origin.
            .securityValidator(DefaultServerTransportSecurityValidator.builder()
                    .allowedOrigins(List.of("http://localhost:8901"))
                    .build())
            // This example admits every caller as one local consumer. A real host
            // authenticates the request first and records the principal it admitted.
            .contextExtractor(request -> McpTransportContext.create(Map.of("consumer", "local-agent")))
            .build();
}

@Bean
public ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(HttpServletStatelessServerTransport transport) {
    return new ServletRegistrationBean<>(transport, "/mcp");
}

@Bean(destroyMethod = "close")
public McpToolServer mcpToolServer(HttpServletStatelessServerTransport transport, NucleoRuntime runtime) {
    McpToolServer server = new McpToolServer();
    server.setScanPackages(List.of("ai.redouble.examples"));   // every @MCP tool of the examples
    server.setProviders(List.of());
    server.setConsumerResolver(context -> context.get("consumer") instanceof String name
            ? new McpConsumer(name, Set.of())
            : null);
    server.setAccessPolicy(new StaticGrantsAccessPolicy(Map.of("local-agent", List.of("order_status"))));
    server.setServerName("orders");
    server.setServerVersion("1");
    server.setCallTimeout(Duration.ofMinutes(1));
    server.setTransport(transport);
    server.start();
    return server;
}
```

Reading it from the top:

**The transport** is the MCP SDK's stateless HTTP servlet, registered at `/mcp` like any
servlet. Its context extractor records who is calling for the beans below to read. This
example admits everyone as one consumer, `local-agent`; a real host authenticates the
request first - the way it authenticates any request - and records the principal it
admitted.

**The server** is Nucleo's `McpToolServer`. It scans the named packages for `@MCP` tools,
turns each caller into a consumer through the resolver, and answers one question through
the access policy: may this consumer use this tool. The same answer decides what the
consumer sees listed and what it may call, so grants cannot be probed. `setCallTimeout`
bounds a call; a call that outlives it has its whole workflow cancelled.

What a caller receives is the tool as this application declared it: the input schema
generated from `OrderNumber`, the description from `@ToolDescription`, and results as the
tool's output object in JSON. Everything a caller sends is checked against that schema
before the tool runs, and every call runs as a job under the caller's name, recorded like
any other work ([Offering your tools over MCP](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/mcp/server/PACKAGE.md)).

The application listens on port 8901 (`application.yaml`), beside the demo's 8080.

## Next

[Calling an MCP server](../mcpclient/PACKAGE.md) connects to this application from
another process.
