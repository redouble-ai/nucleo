# Package: ai.redouble.nucleo.mcp.server

The other direction from [Calling MCP servers](../PACKAGE.md): your process becomes the MCP
server, and outside MCP clients call your tools. A client may be a person's assistant (Claude
Desktop, Cursor), another company's agent, or another Nucleo process. Offer a tool this way
when it should be usable from outside your application by any client that speaks the
protocol, under your own access rules.

A served tool runs exactly as it does in your process: as a job, under the caller's identity,
with the tool's own guardrails and the dispatcher's admission. What this package adds is the
door: the list of what may be offered, who may call what, a strict check of everything that
arrives, and nothing a caller sent ever echoed back. It uses no servlet or Spring type. Your
application supplies the HTTP transport, the way to tell who is calling, and the access
rules; the package supplies everything else.

## Marking a tool as offerable

The server finds the tools it offers by scanning packages, and it takes a tool only when its
class carries `@MCP` beside its `@ToolName`, as the PubMed search tool of nucleo-ext-lit does
(its other annotations left out here):

```java
@MCP
@ToolName("search_pubmed")
public class PubMedSearchTool extends AbstractTool<PubMedSearchInput, PubMedSearchOutput> {
```

`@MCP` makes a tool offerable, and each caller then sees, of the offerable tools, only what the
access policy grants it. A tool can also be handed to the server directly, as a provider,
below. The server refuses to start on an `@MCP` class without `@ToolName`, on two classes
claiming one name, on a tool whose input schema could not be generated, and on nothing to
serve at all.

## Starting the server

```java
McpToolServer server = new McpToolServer();
server.setScanPackages(List.of("ai.redouble.nucleo.ext"));
server.setProviders(List.of());   // required; empty when only scanned tools are served
// YourPrincipal is the host's own principal type, put on the context by its transport
server.setConsumerResolver(ctx -> ctx.get("principal") instanceof YourPrincipal p ? new McpConsumer(p.name(), p.groups()) : null);
server.setAccessPolicy(new StaticGrantsAccessPolicy(Map.of("research-agents", List.of("search_pubmed", "fetch_pmc_fulltext"))));
server.setServerName("research-tools");
server.setServerVersion("1");
server.setCallTimeout(Duration.ofMinutes(10));
server.setTransport(httpServletStatelessTransport);   // one endpoint; every dialect is served through it
server.start();
// ... on shutdown, after the container has drained:
server.close();
JobDispatcher.getInstance().shutdown(10_000);
```

Every setter is required; `start()` refuses a missing one by name.

- **What to serve.** `setScanPackages` names the package prefixes searched for `@MCP` tools,
  and `setProviders` adds tools by hand. A tool arriving both ways is served once.
- **Who is calling.** The consumer resolver turns a request into an `McpConsumer`: a name,
  which every job the call runs becomes the principal of (`Job.workflow(name, "mcp")`), and
  the groups the caller belongs to. It reads whatever the transport recorded when it
  authenticated the request. Null, or a throw, means unauthenticated: such a request lists
  nothing and calls nothing.
- **Who may call what.** The access policy answers one question, whether this consumer may
  use this tool, and the same answer decides both what the consumer sees listed and what it
  may call. `StaticGrantsAccessPolicy` grants from a map keyed by consumer name or group name;
  its `requireResolvable(catalog)` fails startup on a grant that names a tool the server does
  not serve. A host that already models who may do what answers `McpAccessPolicy` from that
  instead. A policy that throws refuses.
- **How long a call may take.** `setCallTimeout` has no default, because a served tool may be a
  single lookup or a multi-agent investigation. A call that outlives it has its whole workflow
  cancelled, sub-agents included, and the caller gets an error.
- **The transport.** The only transport this package accepts is the SDK's stateless HTTP one,
  `HttpServletStatelessServerTransport`, mounted at one endpoint. Give it
  `McpBoundaryJson.strictMapper()` as its JSON mapper, which refuses duplicate keys and the
  other things a lenient JSON reader lets through. STDIO servers use a different SDK
  interface and cannot be plugged in.

`close()` closes the server and its transport and does not wait for calls in flight, so the
host drains its container first and shuts the dispatcher down after.

## What a caller sees

A caller lists the tools it may use and calls them. A tool that does not exist and a tool it
may not call get one and the same answer, so grants cannot be probed. Every request method
other than `initialize`, `ping`, `tools/list` and `tools/call` is answered as unknown.

Each listed tool carries the input schema generated from its input class
([Answers as Java objects](../../harness/schema/PACKAGE.md)) as standard JSON Schema, with
every object other than a map closed to undeclared properties. It carries an output schema too when the tool
answers an object, and its cost weight in the tool's `_meta` under `ai.redouble/weight`, so a
caller can tell a seconds-long lookup from an agent that runs for minutes.

A result is the tool's whole output object as JSON, a thinker's reasoning included, with
artifacts carrying their references, so another Nucleo process rebuilds them as the types they
are. A failure is an error result whose `_meta` says whether a corrected input may succeed.

Some clients cannot read every JSON Schema construct, so the same contract is published in
several spellings, and a client asks for the one its model stack reads
([Schema dialects](SERIAL.md)).

## What is refused at the door

Everything a caller sends is checked against the schema it was shown, and anything else is
refused: an undeclared property at any depth, a missing required value, a value of the wrong
type (`"7"` is not an integer), a value outside a declared enum or range, a date in the wrong
form, and text a UTF-8 stream or a text column could not hold. Every refusal is written from
this server's own schema, naming the declared parameter and what it accepts, and never repeats
anything the caller sent. The door judges shape alone: a query string containing `../` is
accepted as text, and refusing on content is the business of the tool's guardrails
([Scope and the trust boundary](../../tools/guardrails/PACKAGE.md)).

## A servlet container host

A servlet host that cannot mount the transport as its own endpoint drives it from a
filter instead, and two differences from a Boot host follow from that structure:

- **The application's filter drives the transport.** The application's
  own filter matches the path first, authenticates, records the consumer on the request, and
  calls the transport. That ordering is what keeps the container's ordinary user resolution -
  which in a development environment will happily resolve a user from the host machine - from
  ever seeing an agent's call.
- **Grants come from wherever the host already models who may do what.** A platform with a
  role and action registry answers `McpAccessPolicy` from it, each exposed tool an action a
  role can hold; a host with no such registry uses `StaticGrantsAccessPolicy` instead. Both
  are one predicate, which is the whole point of that interface.

On a Boot host and a servlet host alike, the transport's context extractor carries two
things: the admitted consumer, and the `dialect` query parameter when a request sends one.
The parameter name is the same on every host, so a client's URL works against any of them.

> **Example:** [Your tools over MCP](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/mcpserver/PACKAGE.md) -
> the order-status tool served from a Spring Boot application in three beans.

## How it works inside

The gate in front of the SDK, the call handler, the published schema's rules, the strict
decoder, how dialects are judged, and the hostile-input test campaigns are in
[Inside the MCP server](MCP_SERVER_INTERNALS.md), for those working on the runtime itself.
