# Inside the MCP client

This page is for people working on the runtime itself. How to give an MCP server's tools to
an agent is in [Calling MCP servers](PACKAGE.md).

## Two tiers

| Layer | Location | I/O Types | Use Case |
|-------|----------|-----------|----------|
| Generic | `ai.redouble.nucleo.mcp` | `MCPToolInput` / `MCPToolResult` | Any MCP server, quick integration |
| Typed | a wrapper class per server | Typed POJOs | Specific servers, full LLM support |

## Design Philosophy

MCP support follows the same principles as the rest of Nucleo:

1. **Resource control** - MCP connections are finite resources. The framework manages them like connection pools.
2. **Declarative requirements** - Tools declare what they need. The framework allocates before execution.
3. **Transport transparency** - Tools don't know or care if they're using STDIO or HTTP.
4. **Interface-based** - Core abstractions are interfaces, easily extensible.
5. **No builders** - Use no-arg constructors with setters.

## Naming Conventions

| Category | Pattern | Examples |
|----------|---------|----------|
| Shared interfaces/base | `MCP*` | `MCPClient`, `MCPEndpoint`, `MCPToolDescriptor` |
| Generic implementations | `GenericMCP*` | `GenericMCPClient`, `GenericMCPToolAdapter` |
| Typed implementations | `{Name}MCP*` | the server's name, then the tool's (none ship in this repository) |

## Transport Types

### STDIO Transport

The client spawns the MCP server as a subprocess. Communication happens via stdin/stdout pipes.

```
+-------------------+        stdin         +-------------------+
|                   | -------------------> |                   |
|   Our JVM         |                      |  MCP Server       |
|   (Tomcat)        | <------------------- |  (subprocess)     |
|                   |        stdout        |                   |
+-------------------+                      +-------------------+
```

**Characteristics:**
- We spawn the process (like Claude Code does)
- Process stays alive, reused across calls
- Killed after idle timeout or on shutdown
- Requires `STDIOEndpointRateLimiter`

### HTTP Transport

The MCP server runs independently. We connect via HTTP with optional SSE streaming.

```
+-------------------+       HTTP/SSE       +-------------------+
|                   | -------------------> |                   |
|   Our JVM         |                      |  MCP Server       |
|   (Tomcat)        | <------------------- |  (anywhere)       |
|                   |                      |                   |
+-------------------+                      +-------------------+
```

**Characteristics:**
- Server runs externally (same machine, remote, cloud)
- No process management on our side
- Holds a slot on the shared HTTP pool's admission gate (`HttpConnectionPools.gate()`); the SDK transport opens its own connection

## Resource Management

### STDIO Endpoint Pool

STDIO connections consume system resources (processes, memory, file descriptors). The pool manages the admission accounts; client caching and circuit-breaking live in `MCPClientPool`, and `STDIOEndpointReaper` evicts idle endpoints' clients from there.

```
+---------------------------------------------------------------+
|                    STDIOEndpointPool                          |
|                    (global max: McpSettings.stdioPoolMax)     |
+---------------------------------------------------------------+
|                                                               |
|  Global gate mcp:stdio: caps STDIO subprocesses system-wide  |
|                                                               |
|  Rate Limiters (keyed by endpoint ID, labelled by name):       |
|  +---------------------------+  +---------------------------+  |
|  | id:   "npx -y @brave/..." |  | id:   "npx -y @gh/..."    |  |
|  | name: "brave-search"      |  | name: "github"            |  |
|  | max concurrent: 10        |  | max concurrent: 10        |  |
|  +---------------------------+  +---------------------------+  |
|  (health snapshot shows "mcp:brave-search", "mcp:github")     |
|                                                               |
+---------------------------------------------------------------+
```

**The STDIO pool's responsibilities:** the admission accounts - the pool-wide `mcp:stdio`
gate and one limiter per endpoint, both `CountingGate`s. A STDIO tool declares both, so
admission takes both slots in the job's grant, at once or not at all (prevents system
exhaustion without ever holding one slot while waiting for the other).

**Client caching and circuit-breaking live one level up, in `MCPClientPool`**, which is
transport-agnostic and serves STDIO and HTTP endpoints alike: one shared `MCPClient` per
endpoint id, reused across tool invocations, and after a connection failure the endpoint
fails fast for 2 minutes instead of paying another 20-second handshake timeout.

### HTTP Transport

An HTTP tool declares `setRequiresHttpConnection(true)`, which puts a slot on the gate of the
shared pool from `HttpConnectionPools` into its admission demand. The SDK's transport makes
the request on its own HTTP client.

### Resource Declaration Pattern

The `MCPClient` knows what resources it needs. Tools delegate to the client:

```java
public class GenericMCPToolAdapter extends AbstractTool<MCPToolInput, MCPToolResult> {
    private final MCPClient client;
    private final MCPToolDescriptor descriptor;
    private final MCPHandle handle;

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        switch (client.getEndpoint().getTransportType()) {
            case STDIO -> {
                req.requireRateLimiter(client.getRateLimiter(), null);
                req.requireRateLimiter(STDIOEndpointPool.globalGate(), null);
            }
            case HTTP -> req.setRequiresHttpConnection(true);
        }
        return req;
    }
}
```

The transport decides the resources a call holds: for STDIO a slot on the endpoint's own
limiter and a slot on the pool-wide subprocess cap, declared separately so admission reserves
both for the head of its queue; for HTTP a slot on the shared HTTP pool's gate. The tool's
execution is the same either way. `getRateLimiter()` answers the endpoint's limiter for a
STDIO client and null for an HTTP one.

## Core Interfaces

### MCPEndpoint

Interface for endpoint configuration. Implementations describe HOW to connect and know how to create their own transport.

```java
public interface MCPEndpoint {
    /**
     * Returns the transport type for this endpoint.
     */
    MCPTransportType getTransportType();

    /**
     * Returns a unique identifier derived from the endpoint configuration.
     * For STDIO: command + args (e.g., "npx -y @brave/brave-search-mcp-server")
     * For HTTP: the URL
     */
    String getEndpointId();

    /**
     * Creates the appropriate transport for this endpoint.
     */
    McpClientTransport createTransport(ObjectMapper objectMapper);

    /**
     * Per-call timeout for responses from this endpoint, passed to the SDK client's
     * requestTimeout. 20 seconds unless an endpoint overrides it.
     */
    default Duration getRequestTimeout() {
        return Duration.ofSeconds(20);
    }
}
```

### STDIOMCPEndpoint

STDIO transport endpoint - spawns a subprocess.

```java
public class STDIOMCPEndpoint implements MCPEndpoint {
    private String name;                       // logical label, e.g. "brave-search"
    private String command;                    // e.g., "npx", "python"
    private List<String> args;                 // e.g., ["-y", "@mcp/server-github"]
    private Map<String, String> environment;   // env vars for subprocess
    private Duration requestTimeout;           // null = the MCPEndpoint default of 20 seconds

    // No-arg constructor + getters/setters, including getRequestTimeout / setRequestTimeout
}
```

The `name` is mandatory when the endpoint is registered with `STDIOEndpointPool` - `STDIOEndpointRateLimiter#limiterName` fails fast if it's missing. It is independent of `getEndpointId()`: the name is the human-readable label shown in health snapshots and logs, the endpoint id (command + args) remains the cache/refcount key.

**Endpoint identity (cache key) includes env.** `getEndpointId()` folds a deterministic hash of the sorted environment map into the identity string alongside command and args (as a ` #env:<hash>` suffix). Two endpoints that spawn the same binary with the same args but different env (e.g. `APP_FS_ROOT` pointing at different sandbox directories) produce different IDs and are cached as distinct clients - and therefore distinct subprocesses - by `MCPClientPool`. Env-as-startup-scope is the standard contract for many MCP servers (filesystem roots, per-user identity, dataset paths), so cache keys must reflect it or scope-isolation breaks silently. The env is hashed rather than inlined because it commonly carries secrets (API keys) and this identity is logged. Endpoints with no env keep the bare `command + args` id.

### HTTPMCPEndpoint

HTTP transport endpoint - connects to remote server.

```java
public class HTTPMCPEndpoint implements MCPEndpoint {
    private String url;                        // MCP server URL
    private Map<String, String> headers;       // Auth headers, applied via the SDK's httpRequestCustomizer hook
    private Flavor flavor = Flavor.SSE;        // SSE (legacy, the default) or STREAMABLE_HTTP
    private McpClientCredential credential;    // the agent identity to connect as (see the auth package)

    // No-arg constructor + getters/setters

    @Override
    public McpClientTransport createTransport(ObjectMapper objectMapper) {
        // Creates the SDK transport for the flavor; a credential installs a McpTokenSupplier
    }
}
```

It does not override `getRequestTimeout()`, so an HTTP call waits the 20-second default.

**Endpoint identity includes who connects.** `getEndpointId()` is the URL, plus
` #as:<usr>` when a credential is set, plus ` #headers:<digest>` when headers are set
(a digest, as with STDIO env, because headers commonly carry an API key and the id is
logged). Two endpoints to one URL under different identities are different clients to
`MCPClientPool`, so one consumer never rides another's session.

**Credentials.** `setCredential` takes an `AgentSecret` or an `AgentSigningKey` from
`ai.redouble.nucleo.mcp.auth`; the endpoint then mints bearer tokens through the MCP
authorization flow itself and attaches one to every request. Only the streamable
flavor can drop a token the server rejected, so a credential on the SSE flavor is
refused, as is a credential alongside an `Authorization` header.

### MCPClient

Main interface for interacting with MCP servers.

```java
public interface MCPClient extends Closeable {
    /**
     * Returns the rate limiter for this client.
     * Jobs should declare this rate limiter in their requirements.
     */
    RateLimiter<Void> getRateLimiter();

    /**
     * Lists all tools available on this MCP server.
     */
    List<MCPToolDescriptor> listTools() throws LLMReadableCheckedException;

    /**
     * Calls a tool by name with the given arguments.
     */
    MCPToolResult callTool(String toolName, JsonNode arguments) throws LLMReadableCheckedException;

    /**
     * Returns server information from initialization.
     */
    MCPServerInfo getServerInfo();

    /**
     * Checks if the connection is alive.
     */
    boolean isConnected();

    /**
     * Returns the endpoint configuration for this client.
     */
    MCPEndpoint getEndpoint();

    /**
     * Closes the client and releases resources.
     */
    @Override
    void close();
}
```

### MCPConnector & MCPConnectorRegistry

`MCPConnector` is one attached server: an `MCPHandle` + an `MCPEndpoint` + the lazily-fetched, failure-tolerant `providers()` list. Its lifecycle is owned by the registry, not the caller.

`MCPConnectorRegistry` is the process-wide registry. It is keyed by the composite identity `(handle, endpointId)`, so the same logical handle can be attached to several distinct endpoints concurrently - the case where one MCP server type runs against multiple isolated backends at once (per-sandbox filesystem roots, per-tenant datasets, per-world harnesses). Those attaches resolve to separate connectors instead of colliding on the handle. Note `MCPHandle` value plays a double role: registry identity AND the LLM-facing tool-name prefix (`namespacedToolName`), so the world/instance dimension belongs in the endpoint id, never in the handle value.

```java
MCPConnector connector = MCPConnectorRegistry.attach(handle, endpoint);
// ... use connector.providers() ...
MCPConnectorRegistry.detach(connector);   // symmetric: hand back what attach returned
```

Two refcount levels keep teardown correct once connector identity and client identity diverge:

| Level | Tracks | Drives |
|---|---|---|
| Per-connector | live attaches of one `(handle, endpointId)` | connector removal (last detach wins) |
| Per-endpoint | distinct connectors referencing one endpoint id | `MCPClientPool` client eviction (shared client survives until the last referencing connector is gone) |

`attach`/`detach` are concurrency-safe: per-connector state is mutated only inside the `connectors` map's `compute` lambda, while the endpoint refcount is touched only outside it, so the two maps are never locked in a nested order. Connecting (`providers()`) and client eviction both run outside the lambda so no network or subprocess I/O runs under a map bin lock. `detach` takes the `MCPConnector` (which carries its own key) rather than the handle, because a bare handle is no longer a sufficient teardown token under the composite key.

`MCPToolProvider` does not capture a client: each provider's `create` wires a `GenericMCPToolAdapter` on demand, against a live client from `MCPClientPool`, so a closed or evicted client never poisons the palette.

## Data Classes

### MCPToolDescriptor

Raw tool description from MCP server.

```java
public class MCPToolDescriptor {
    private String name;
    private String description;
    private JsonNode inputSchema;  // JSON Schema for tool parameters
}
```

### MCPToolResult

Result from an MCP tool call. The container; `resultArtifact` is the artifact, populated by `GenericMCPToolAdapter` after the call returns.

```java
public class MCPToolResult  {
    @JsonIgnore
    private List<MCPContent> content;   // raw content; programmatic callers only
    private Artifact resultArtifact;    // the artifact, what the LLM sees

    public String getTextContent();
    public String getAllTextContent();
}
```

`content` is `@JsonIgnore`'d so LLM-facing serialization sees a single canonical artifact representation rather than both the inlined content list and a ref to the same payload. Direct callers - typed wrappers over a specific server - extract data from `getContent()` programmatically and never reach Jackson.

`resultArtifact` is an `Artifact`, not an `MCPArtifact`: a server that is itself a redouble process serves artifacts that rebuild into their own types, and the generic `MCPArtifact` is what an untyped payload degrades to rather than what every payload is.

### MCPArtifactRehydrator

Rebuilds a typed `Artifact` from a result served by another redouble process. Nothing is negotiated and no capability is advertised: an artifact serializes with its `artifact_ref`, and the ref carries the `@TypeAlias` of the class that wrote it (`«artifact:link:cite:pubmed~a7f3b2»`), so the wire form already says what it is. A third-party client sees one extra string field and ignores it. This is the same round trip a conversation restore performs in `ArtifactMapDeserializer`.

The consumer does not need the exact class. `TypeAliasRegistry.resolveAliasWithFallback` walks the alias up its colon hierarchy, so the ladder is:

| The consumer holds | It gets |
|---|---|
| the exact class | that type, fields and ref intact |
| an ancestor (`LinkArtifact` but not `PubMedArticle`) | the ancestor, with the fields it declares |
| neither | null, and `GenericMCPToolAdapter` keeps the generic `MCPArtifact` path |

Scope is the ROOT of the result: a served tool whose output is an artifact, single or a `ListArtifact` of them, arrives as that artifact. A result that is a plain POJO with artifacts in its fields takes the generic path whole, because the containing type is what a foreign consumer is least likely to hold.

Refs are preserved as the producing process minted them, so one ref names the same thing in both processes' logs. Summaries are not carried: `summaryCache` is transient, so an arriving artifact is newly born and the consuming harness summarizes it under its own annotations and its own policy.

`GenericMCPToolAdapter` tries `MCPArtifactRehydrator` first on each result and falls back to `MCPResultWalker`. The order is what makes typed artifacts from a redouble server transparent and leaves every third-party server on the generic path, decided by what is in the payload rather than by knowing who the server is.

### MCPArtifact

Generic artifact wrapping an MCP tool's structured response. Behaves like every other `AbstractArtifact`: registered in the `ArtifactRegistry` on serialization, replaced by its ref in LLM-facing output, fetchable by ref via `get_artifact_field`.

```java
@TypeAlias("mcp")
public class MCPArtifact extends AbstractArtifact {
    @LLMSummarizable(value = "MCP tool result data", preSummarized = true)
    private Map<String, Object> data;
    private String mcpHandle;
    private String toolName;
}
```

The `preSummarized = true` flag tells the framework's serializer to walk `data` and consult the artifact's inherited `summaryCache` at each leaf path (JSON pointer). Cache hits render the cached summary; cache misses render the raw value. The walker that produces the artifact (see below) is the cache populator.

### MCPResultWalker

Pure transformation of a `JsonNode` into an `MCPArtifact`. No registry, no I/O. Two rules:

| Condition | Action |
|---|---|
| Object value whose serialized form > 2000 bytes | Promoted to its own nested `MCPArtifact`, embedded as the value at that key in the parent's `data`. The framework's `ArtifactRefSerializer` collapses each nested instance to `{"@ref": ...}` in LLM-facing serialization. |
| String leaf longer than 1000 chars | Full text stays in `data`; the artifact's `summaryCache` gets an entry keyed by JSON pointer (`/results/0/fullText`) carrying a `TruncatingSummarizer` summary. |
| Anything smaller | Passes through verbatim. |

Pointer paths reset to root inside each promoted nested artifact - each artifact's cache is keyed relative to its own `data`. `GenericMCPToolAdapter.execute` invokes the walker after the tool call and attaches the result to `MCPToolResult.resultArtifact`.

### MCPContent

Content item in a tool result.

```java
public class MCPContent {
    private String type;      // "text", "image", "resource"
    private String text;      // for type="text"
    private String mimeType;  // for binary
    private String data;      // base64 for binary
}
```

### MCPToolInput

Input wrapper for generic MCP tool calls.

```java
public class MCPToolInput  {
    private JsonNode arguments;
}
```

## Exceptions

MCP uses the framework's standard exception types directly; there are no service-specific
exception classes - see `LLMReadableException` javadoc for the rationale. HTTP authorization
failures surface from the SDK as its own authorization exception and are mapped to
`UnauthorizedException`, and a typed exception the endpoint's token flow threw is rethrown as
itself rather than wrapped as an external failure.

## Normalizing an External Server's Schema

`MCPSchemaNormalizer` runs over each remote tool's input schema before it reaches an
LLM-facing `ToolDefinitionBlock`. It aims at faithful pass-through: semantically meaningful
constructs flow through verbatim, and it does three things only. It inlines same-document
references, because a model reads a definition where it is used and clients that meet a bare
`$ref` have been seen to stringify the object or send null; an external or unresolvable
reference collapses to `{"type":"object"}`, and a cycle is the one shape that cannot be
inlined, so it keeps its `$ref` and its `$defs` entry, which every provider this process
talks to accepts. It strips the four keywords some provider tool APIs refuse outright. And it
rejects a schema whose top level is neither an object nor a properties map, which makes
`MCPToolProvider` skip that one tool and register the rest of the catalog.

The inlining is `SchemaRewrites.inlineForeignRefs` from `ai.redouble.nucleo.mcp.server` - the
same walk our own dialects use, under its foreign policy.

## STDIO Endpoint Lifecycle

```
MCPToolProvider.create(): MCPClientPool.getConnectedClient(endpoint)
    |
    +-- Client cached and connected? -> return it (fast path)
    |
    +-- Circuit breaker open? -> throw ExternalServiceException (fail fast)
    |
    +-- Create new client via GenericMCPClient.connect(endpoint)
        |
        +-- Success -> cache client, clear circuit breaker, return
        |
        +-- Failure -> record failure time, throw (circuit breaker opens for 2 min)
    |
    v
Job admitted: Admission granted the tool's whole demand at once,
including one slot on the endpoint limiter and one on the mcp:stdio gate
    |
    v
execute(): the tool call via MCPClient.callTool()
    |
    v
JobResources.close(): Admission.release(grant)
    |
    +-- Give back the per-endpoint slot
    |
    +-- Give back the mcp:stdio slot
    |
    v
Update last activity timestamp
    |
    ... no activity for idleTimeout ...
    |
    v
STDIOEndpointReaper evicts the client from MCPClientPool
(closing it kills the subprocess; the next use connects fresh)
```

The client is obtained when the adapter is created, before the job is admitted, because the
adapter's requirements name the client's own limiter.

## Package Structure

```
ai.redouble.nucleo.mcp/
    +-- MCPTransportType.java             # Enum: STDIO, HTTP
    +-- MCPEndpoint.java                  # Endpoint interface
    +-- STDIOMCPEndpoint.java             # STDIO endpoint impl
    +-- HTTPMCPEndpoint.java              # HTTP endpoint impl
    |
    +-- MCPToolDescriptor.java            # Raw tool schema from MCP
    +-- MCPToolResult.java                # Tool call result (carries the result artifact)
    +-- MCPToolInput.java                 # Tool input wrapper
    +-- MCPContent.java                   # Content item in result
    +-- MCPServerInfo.java                # Server metadata
    +-- MCPArtifact.java                  # Artifact wrapping the structured result
    +-- MCPArtifactRehydrator.java        # Result text -> the typed artifact a redouble server sent
    +-- MCPResultWalker.java              # JsonNode -> MCPArtifact (pure)
    |
    +-- MCPClient.java                    # Client interface
    +-- MCPClientPool.java                # Per-endpoint client reuse
    +-- GenericMCPClient.java             # Implementation for any MCP
    +-- GenericMCPToolAdapter.java        # Generic Tool wrapper
    +-- MCPToolProvider.java              # One remote tool as a ToolProvider
    +-- MCPSchemaNormalizer.java          # Inbound schema normalization
    |
    +-- MCP.java                          # Marks a Nucleo tool exposable
    +-- McpSettings.java                  # The package's knobs
    |
    +-- MCPHandle.java                    # Logical handle / tool-name prefix
    +-- MCPConnector.java                 # One attached server (handle + endpoint + providers)
    +-- MCPConnectorRegistry.java         # Process-wide connector registry
    |
    +-- STDIOEndpointPool.java            # Global STDIO pool
    +-- STDIOEndpointRateLimiter.java     # RateLimiter<Void> impl for STDIO
    +-- STDIOEndpointReaper.java          # Daemon evicting idle endpoints' clients
    |
    +-- auth/                             # OAuth 2.1 client flow for a remote server
    +-- server/                           # Serving Nucleo tools to external clients
    |                                     #   (incl. SERIAL.md, the schema spellings we publish)
    +-- PACKAGE.md                        # The guide
    +-- MCP_INTERNALS.md                  # This file
```

## Dependencies

This package requires the official MCP Java SDK, which nucleo-core declares:

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-core</artifactId>
</dependency>
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-json-jackson2</artifactId>
</dependency>
```

The version is managed by the reactor's BOM, `nucleo-bom` (`mcp.sdk.version`).

## Thread Safety

| Component | Thread Safety |
|-----------|---------------|
| `STDIOEndpointPool` | Thread-safe (ConcurrentHashMap of limiters, synchronized initialization) |
| `STDIOEndpointRateLimiter` | Thread-safe (a `CountingGate`: atomic in-use count, takes under `Admission`'s exclusion) |
| `MCPClientPool` | Thread-safe (ConcurrentHashMap cache, synchronized client creation) |
| `STDIOEndpointReaper` | Thread-safe (runs on daemon thread) |
| `GenericMCPClient` | Thread-safe (MCP SDK handles JSON-RPC message correlation) |
| `GenericMCPToolAdapter` | Single-threaded (one instance per invocation) |

## References

- [MCP Specification](https://modelcontextprotocol.io/specification/2025-11-25)
- [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)
- [MCP Java SDK Documentation](https://modelcontextprotocol.io/sdk/java/mcp-overview)
