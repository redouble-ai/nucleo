# Inside MCP authorization

This page is for people working on the runtime itself. How to connect to an MCP server as an
agent is in [MCP authorization](PACKAGE.md).

## Classes

| Class | Role |
|---|---|
| `McpClientCredential` | sealed: what the agent presents; names the agent's `usr` (the OAuth client id) and the token endpoint method it needs |
| `AgentSecret` | a static secret, sent as HTTP Basic at the token endpoint |
| `AgentSigningKey` | a private key (RSA = RS256, EC P-256 = ES256), from the PKCS#8 PEM the minter printed; optional `kid` |
| `ClientAssertion` | the RFC 7523 assertion: `iss` = `sub` = usr, `aud` = the authorization server's issuer identifier, fresh `jti`, one-minute life; signed with the JDK, JSON by the framework serializer |
| `McpAuthorizationDiscovery` | the URL arithmetic: challenge parsing, canonical resource, the well-known forms in the specification's order |
| `ProtectedResourceMetadata`, `AuthorizationServerMetadata` | what the client reads from the two documents |
| `McpTokenClient` | the flow, on `AbstractApiClient`: discovery, metadata, token request; documents read by the boundary's strict reader (`McpBoundaryJson`), so a duplicate key, a comment, or bytes after the document are refused rather than guessed at |
| `McpAccessToken` | the minted token and its expiry (null = used until refused) |
| `McpTokenSupplier` | per transport: attaches the held token, mints when none is held or it has expired, forgets one the server rejected; single flight |

## The flow

1. The protected resource metadata: the path-inserted well-known form, then the root form
   (RFC 9728 section 3). When neither answers, an unauthenticated JSON-RPC ping is sent and
   the bearer challenge's `resource_metadata` URL is followed (RFC 9728 section 5.1). A
   server that answers the probe with anything but a 401 challenge does not do what a
   configured credential assumes, and the flow says so.
2. The document must describe the resource it was fetched for (RFC 9728 section 3.3): the
   canonical MCP URL, scheme and host lowercased, default port dropped, no trailing slash.
3. The first authorization server's metadata, at the well-known forms for its issuer shape
   (RFC 8414 section 3, OpenID Discovery). The document must name that issuer (RFC 8414
   section 3.3) and a token endpoint on the issuer's host under the issuer's scheme: a
   token or a secret never travels to a host the metadata did not name, and never down
   from https to http. Absent `grant_types_supported` means the RFC 8414 default, which
   has no client-credentials grant; absent `token_endpoint_auth_methods_supported` means
   `client_secret_basic` only, so a secret still mints and a signing key does not. Every
   URL a document or a challenge names must be absolute and http or https. Fields the
   client does not read are ignored; a document that is not a JSON object, a status that
   carries no body (204), or a success served as anything but `application/json` (RFC
   8414 section 3.2, RFC 6749 section 5.1) is refused.
4. The token request: `grant_type=client_credentials`, `resource=<canonical MCP URL>`
   (RFC 8707), and either `client_assertion_type` + `client_assertion` (no `client_id`; the
   extension conveys the client through `sub`) or HTTP Basic `usr:secret`. The assertion's
   audience is the issuer identifier, which is what RFC 7523bis and the conformance referee
   require; a server following the older token-endpoint audience also accepts it when it
   validates both, as Spring's does.

## What the transport does with it

`HTTPMCPEndpoint.setCredential` installs a `McpTokenSupplier` on the streamable transport:
the SDK's request customizer runs the supplier before every request, so the first request
of a fresh transport already carries a token and an expired token is replaced before it
is sent. The SDK's authorization error handler only invalidates: its retry re-sends the
original request unchanged, so a token minted in reaction to a 401 could never ride it.
A rejected token therefore costs exactly one failed call (`UnauthorizedException`), and
the next call mints; a mint the authorization server refuses surfaces from `connect`,
`listTools`, and `callTool` alike as the typed exception the flow threw, never as an
external failure wrapping it. The SSE transport has no such handler; a credential on it is
refused.

## What never appears in a message

The secret, the private key, the assertion, the token. Credentials and tokens render
without their values; failures are composed from the step, the URL fetched, and the
status. Upstream bodies are quoted as received, which the base client does for every
third-party service; the token endpoint's error body names an OAuth error code and
nothing of the request.

## Redirects

Never followed: a 3xx from any document or the token endpoint is a failure whatever body
rides with it (`AbstractApiClient` maps any 3xx that reaches it, which only a request with
redirects disabled lets happen, to the typed failure naming the endpoint), because the
alternative is sending the credential to wherever the redirect points. The token response must carry a non-empty
string `access_token` shaped as an RFC 6750 b64token (it goes into a header) and a
`token_type` of `Bearer` in any case. `expires_in`, when present at all, must be a
non-negative integer a long holds and an instant can carry; anything else, JSON null
included, is refused rather than truncated; absent means the token is used until refused.

## Conformance

`ConformanceClientMain` (test scope) is the client under test for
`auth/client-credentials-jwt` and `auth/client-credentials-basic` of
`@modelcontextprotocol/conformance`; `HttpMcpCredentialFlowTest` drives the real transport
against an in-process server scripted to the referee's checks.
