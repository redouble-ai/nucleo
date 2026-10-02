# Package: ai.redouble.nucleo.mcp.auth

Some MCP servers serve only callers that prove who they are. The MCP authorization
specification (2025-11-25) says how: the server names an authorization server, the client
obtains an access token from it with credentials issued to that client, and sends the token
with every request. This package does that for an agent calling a server as itself, with no
person logging in, which is the specification's client-credentials extension
(`io.modelcontextprotocol/oauth-client-credentials`). It works against any conformant server.
Issuing the credentials and running the authorization server are the server side's business,
and nothing here depends on them beyond the protocol.

## Connecting as an agent

An agent's credential is its `usr`, the OAuth client id the server knows it by, together with
either a secret or a private key. Give it to the endpoint, and the endpoint obtains, attaches
and renews its tokens itself:

```java
Credential agent = Secrets.configured().require("research-mcp-agent");

HTTPMCPEndpoint endpoint = new HTTPMCPEndpoint();
endpoint.setUrl("https://mcp.example.com/mcp");
endpoint.setFlavor(HTTPMCPEndpoint.Flavor.STREAMABLE_HTTP);
endpoint.setCredential(new AgentSecret(agent.user(), agent.secret()));
```

The secret comes from the deployment's credential store like any other key: under the
store's one rule, `research-mcp-agent` reads `RESEARCH_MCP_AGENT_USER` and
`RESEARCH_MCP_AGENT` from the environment ([Credentials](../../secrets/PACKAGE.md)).

- **`AgentSecret`** is a static secret, sent to the token endpoint as HTTP Basic.
- **`AgentSigningKey`** is a private key whose public half the server holds; the client signs
  a one-minute assertion with it for each token. `AgentSigningKey.fromPem(usr, pem, keyId)`
  reads the PKCS#8 PEM the key was issued as. An RSA key signs RS256 and an EC P-256 key
  ES256; the algorithm follows from the key. `keyId` names the key to a server holding several
  for the agent, and null leaves the server to try each.

Two combinations are refused when the connection is made: a credential on the `SSE` flavor,
because only the streamable transport can drop a token the server rejected, and a credential
together with an `Authorization` header, because an endpoint connects as one identity.

## What the agent sees

The first request of a connection already carries a token, and an expired token is replaced
before it is sent. A token the server rejects costs exactly one failed call, as an
`UnauthorizedException`, and the next call obtains a fresh one. When the authorization
server refuses to issue a token, `connect`, `listTools` and `callTool` all fail with the
exception the flow composed, naming the step that failed.

## What never appears in a message

The secret, the private key, the assertion and the token. Credentials and tokens print
without their values, and failures are composed from the step, the URL fetched, and the
status. A redirect from any document or from the token endpoint is a failure, never
followed, because following it would send the credential wherever it points.

## How it works inside

The discovery steps, the checks on each document, the token request, and the conformance
tests are in [Inside MCP authorization](MCP_AUTH_INTERNALS.md), for those working on the
runtime itself.
