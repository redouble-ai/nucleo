# Package: ai.redouble.nucleo.mcp

Some tools you do not write, because they already run somewhere else: a vendor's document
parser, a chemistry toolkit, a colleague's service. Many such programs speak the Model
Context Protocol (MCP), an open standard, created by Anthropic, for offering tools to AI
applications. A program that offers tools this way is an MCP server; it lists its tools,
each with a name, a description and a JSON Schema for its arguments, and runs one when a
client asks. Any application that speaks the protocol can use them without code written for
that server.

Use an MCP server's tools when the operation already lives in another process and its owner
publishes it over MCP. Your agent then calls it like any tool of its own: the call is a job,
admitted and recorded like every other, and its result reaches the model as an artifact, data the model cannot alter
([Artifacts](../harness/artifacts/PACKAGE.md)). When the operation is yours, in your process,
write it as a tool ([Tools, thinkers and doers](../tools/PACKAGE.md)); when outside clients
should call your tools, offer them from your process with
[Serving tools over MCP](server/PACKAGE.md).

This package is the client side: it connects to servers, turns what they offer into tools a
thinker (an agent whose model decides which tool to call next) can be given, and manages the
connections as the finite resources they are. Everything it needs, the official MCP Java SDK
included, comes with nucleo-core.

## Giving a server's tools to an agent

Describe how to reach the server, attach it under a short name, and add what it offers to a
thinker:

```java
HTTPMCPEndpoint endpoint = new HTTPMCPEndpoint();
endpoint.setUrl("https://mcp.example.com/v1");
endpoint.setFlavor(HTTPMCPEndpoint.Flavor.STREAMABLE_HTTP);
endpoint.setHeaders(Map.of("Authorization", "Bearer " + apiKey));

MCPConnector connector = MCPConnectorRegistry.attach(new MCPHandle("github"), endpoint);
for (MCPToolProvider provider : connector.providers()) {
    thinker.addTool(provider);   // LLM-facing name: "github_<tool>", schema normalized
}
// ... when nothing needs the server any more:
MCPConnectorRegistry.detach(connector);
```

**The endpoint** says how to connect: its URL and headers for a server running as a service
(`HTTPMCPEndpoint`), or the command that starts it as a subprocess (`STDIOMCPEndpoint`,
below).

**The handle** is the short name the model knows the server's tools by. Every tool is shown
to the model as the handle, an underscore and the tool's own name, so two servers that both
offer `search` stay apart. A handle is lower case, starts with a letter, and has at most 16
letters, digits, underscores or dashes; anything else is refused when it is created.

**Attaching** connects to the server and fetches its list of tools, once. Each entry of
`connector.providers()` is one remote tool, ready to hand to a thinker. Its arguments schema
is the server's own, lightly normalized so every model provider accepts it, and its
description is cut to `McpSettings.descMaxChars` characters. A tool whose schema is neither
an object nor a map of properties is left out with a warning, and the rest of the server's
tools are kept. A server that
cannot be reached gives no tools and a warning in the log, and the next `providers()` call
tries again; after a failed connection the endpoint fails fast for two minutes instead of
waiting on another handshake.

**Detaching** hands back what `attach` returned. Everything attached to one endpoint shares
one connection, which is closed when the last of it detaches.

A tool that one server type runs against several isolated backends at once, such as a
filesystem server per sandbox, is attached once per backend under the same handle: what tells
the attaches apart is the endpoint (a different URL, headers or environment), so the handle
stays the tool-name prefix the model reads.

## Choosing a transport

MCP runs over two transports. They look identical to the agent and to the tool author, and
their operational profiles diverge sharply.

| | STDIO | HTTP |
|---|---|---|
| Server lifecycle | Subprocess of the JVM, lives and dies with our process | Independent service (any host) |
| Wire protocol | stdin/stdout pipes | TCP + HTTP/SSE |
| Multiple clients per server | No - 1:1 with the parent process | Yes |
| Runtime deps on our box | Whatever the server needs (`uv`, `python`, native libraries, `libreoffice`, ...) | None - just an HTTP client |
| Operational surface | We own the subprocess: memory, crashes, orphan reaping, file descriptors | Server runs as its own service with its own monitoring and scaling |
| Cold start cost per endpoint | ~1-2s spawn, then warm reuse | One-time TCP handshake |
| Best fit | Benchmarks, dedicated single-machine runs, local development, CLI-only servers | Production webapps, customer deployments, sporadic traffic |

**Default to HTTP for production-bound integrations.** STDIO inside a long-running webapp
means the application server forks subprocesses, which operations then has to support:
extra runtime dependencies on the box, process limits to monitor, orphan recovery, a pool of
background workers running alongside the webapp. HTTP keeps the MCP server's lifecycle apart
from the JVM, and the server deploys as a normal service with its own image, health checks
and scaling.

**STDIO is the right call when** the workload is hot (benchmark runs, tight agent loops
where many tool calls keep subprocesses warm), the deployment is single-machine and
dedicated, or no HTTP variant of the server exists. An endpoint idle for
`McpSettings.stdioIdleTimeoutSeconds` (300 by default) has its subprocess stopped, and its
next use starts a fresh one, so an idle application eventually has no MCP processes
resident.

### An HTTP server

`HTTPMCPEndpoint` takes the server's URL, the headers every request carries, and the flavor
of HTTP transport the server speaks: `STREAMABLE_HTTP`, the current one, or `SSE`, the
original HTTP+SSE transport, which is the endpoint's default. Match what the server
advertises.

A server that authenticates agents with the MCP authorization flow takes a credential in
place of a header: `setCredential` with an `AgentSecret` or an `AgentSigningKey`, on the
streamable flavor, and the endpoint obtains and renews its tokens itself
([MCP authorization](auth/PACKAGE.md)). Two endpoints to one URL under different credentials
or headers are separate connections, so one consumer never rides another's session.

A call to an HTTP server waits 20 seconds for its answer, the SDK's default and the right
one for stateless servers.

### A server started as a subprocess

```java
STDIOMCPEndpoint endpoint = new STDIOMCPEndpoint();
endpoint.setName("github");
endpoint.setCommand("npx");
endpoint.setArgs(List.of("-y", "@modelcontextprotocol/server-github"));
endpoint.setEnvironment(Map.of("GITHUB_TOKEN", token));
```

The name is required: it labels the endpoint in health snapshots and logs (`mcp:github`),
and an endpoint without one fails fast, saying the name is missing. The environment is part of the endpoint's
identity, so two endpoints that start the same command under different environments, such as
different sandbox roots, run as two subprocesses.

A subprocess server that legitimately needs longer than 20 seconds to answer, such as one
driving a browser through heavy pages, gets `setRequestTimeout`; raising it for stateless
servers only costs them their fast failure.

Subprocesses are limited twice, and admission takes both before a call runs, at once or not
at all: at most `McpSettings.stdioPoolMax` (20) in use across the process, and at most
`McpSettings.stdioMaxConcurrentPerEndpoint` (10) per endpoint.
`STDIOEndpointPool.setEndpointMax(endpointId, max)` sets a different limit for one endpoint;
its id is the command and its arguments.

## What comes back

A call returns an `MCPToolResult`. The model sees it as one artifact: an `MCPArtifact`
holding the server's structured response, in which an object larger than 2000 bytes becomes
a nested artifact the model sees by reference, and a string longer than 1000 characters is
shown shortened while the whole value stays available. A model that needs the full value
asks for it by reference through the artifact tools
([Artifact tools](../harness/artifacts/tools/PACKAGE.md)).

When the server is itself a Nucleo process and a tool's whole result is an artifact, or a list
of them, it arrives as the type it was sent as, as far as this process holds that type: the exact class, else the nearest ancestor it
holds, else the generic `MCPArtifact`.

Your own code reads the raw content of a result with `getContent()`, or its text with
`getTextContent()` (the first text item) and `getAllTextContent()` (every text item).

## When a remote call fails

A failure reaches the model and your code as one of the runtime's own exceptions, which say
whether a corrected call can succeed
([Failures that behave](../harness/errors/EXCEPTIONS.md)):

| MCP Error | Framework Exception | Correctable? |
|-----------|-------------------|--------------|
| Connection/spawn failure | `ExternalServiceException("MCP:endpointId", ...)` | No |
| Protocol error (JSON-RPC) | `ExternalServiceException("MCP:endpointId", ...)` | No |
| Server rejects credentials (handshake or call) | `UnauthorizedException("MCP:endpointId", ...)` | No |
| Tool returned isError=true | `InvalidInputException(toolName, endpointId, ...)` | Yes |
| Client not connected | `ExternalServiceException("MCP:endpointId", ...)` | No |

The server is the authority on its own arguments: nothing is validated before the call, and a
server's refusal comes back as the correctable `InvalidInputException`, so the model can
correct its arguments and call again.

## Calling a server from your own code

Code that calls a server directly, without a model, takes a client from the pool, which keeps
one connected client per endpoint:

```java
MCPClient github = MCPClientPool.getConnectedClient(endpoint);
List<MCPToolDescriptor> tools = github.listTools();
MCPToolResult result = github.callTool("search_repositories",
    objectMapper.valueToTree(Map.of("query", "language:java stars:>1000")));
// No close() - the pool manages the client.
```

`GenericMCPClient.connect(endpoint)` connects a client of your own instead, which you close
when done; closing a subprocess server's client stops the subprocess.

A server used heavily earns a typed wrapper: a tool class per remote tool with typed input
and output beans, its own `@ToolName`, and the same endpoint underneath, reading the result
from `MCPToolResult.getContent()`. The generic path above serves any server used directly.

## Introducing your application

Every connection introduces itself to the server in the protocol's handshake, and servers log
and meter by that name. It belongs to your application: call
`GenericMCPClient.identifyAs(name, version)` once at startup. Until then the runtime presents
itself as `nucleo` at the library's version.

## Settings

The package's settings, on `McpSettings`, assigned by the deployment's configurator or bound
from `nucleo.mcp.*` properties in a Spring Boot or Quarkus application
([Settings and the configurator](../PACKAGE.md)):

| Field | Description | Default |
|-------|-------------|---------|
| `stdioPoolMax` | Global max concurrent STDIO processes | 20 |
| `stdioMaxConcurrentPerEndpoint` | Max concurrent per endpoint | 10 |
| `stdioIdleTimeoutSeconds` | Evict an idle endpoint's client after | 300 (5 min) |
| `descMaxChars` | Truncation cap on a remote tool's description surfaced to the LLM | 4096 |

## Shutting down

`MCPConnectorRegistry.shutdownAll()` detaches every connector, and
`STDIOEndpointPool.shutdown()` stops the idle-subprocess sweep and closes every cached client,
which stops their subprocesses. Neither the Spring Boot starter nor the Quarkus extension
calls them, so an application that uses MCP servers calls both in its own shutdown.

> **Example:** [Calling an MCP server](../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/mcpclient/PACKAGE.md) -
> attach, list, and call a tool generically; then
> [An MCP tool wrapped as your own](../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/mcpwrap/PACKAGE.md),
> the typed wrapper a workflow depends on.

## How it works inside

The resource accounts, the connector registry's reference counting, the exact interfaces, the
result walker and the schema normalizer are in [Inside the MCP client](MCP_INTERNALS.md), for
those working on the runtime itself.
