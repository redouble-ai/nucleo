# Inside HTTP status exceptions

This page is for people working on the runtime itself: how each status exception fills the
semantic parent it sits under, and the rules that keep the carriers and their shared record
consistent. The guide is [HTTP status exceptions](PACKAGE.md). The hierarchy they slot into
is documented in [EXCEPTIONS.md](../EXCEPTIONS.md).

## What is here

| Type | Role |
|---|---|
| `HttpExceptions` | The factory. `throwIfError(service, endpoint, status, body)` returns silently below 400 and throws from 400; `fromStatus` builds the exception without throwing. The factory sees status and body only. |
| `HttpErrorDetail` | The server's answer as a record: status, service, endpoint, body. The endpoint is the path alone; a query string is cut at construction, so a caller's own arguments never come back in a message. Three renderings: `atEndpoint()`, `fromService()`, `llmMessage()`. |
| `HttpErrorResponse` | The interface every status exception implements: `http()` and the four accessors. Test for it to read what the server said without knowing which class was thrown. |
| `Http400Exception`, `Http413Exception`, `Http422Exception` | `InvalidInputException`: correctable, the endpoint as the parameter name, `fromService()` as the rule |
| `Http404Exception` | `ResourceNotFoundException`: correctable, the service as the resource type, the endpoint as the identifier |
| `Http401Exception`, `Http403Exception` | `UnauthorizedException`: uncorrectable, the service as the service name, `atEndpoint()` as the reason |
| `Http429Exception` | `UpstreamThrottleException`: uncorrectable, the service as the service name, `atEndpoint()` as the detail, and the seconds a `Retry-After` asked for (0 through the factory, which sees no headers) |
| `Http500Exception`, `Http502Exception`, `Http503Exception` | `ExternalServiceException`: uncorrectable, the service as the service name, `atEndpoint()` as the detail |
| `HttpUnmappedStatusException` | Any other error status (402, 408, 418, 520): an `ExternalServiceException` that is still an `HttpErrorResponse`, so only a failure to reach the service is ever without a status |
| `UpstreamThrottleException` | The upstream is throttling us through something other than a 429 status (a throttle header, a structured body): an `ExternalServiceException` with advisory `retryAfterSeconds`, 0 when the server gave none. EPO's `CLIENT.RobotDetected` 403 is raised by its client as an `Http429Exception`. |
| `UpstreamFailure` | What a rate limiter records about a failure: status (0 without a response), service, detail, retry-after (0 without one). `from(ExternalServiceException)` walks the cause chain and reads the status and the server's detail off the first `HttpErrorResponse` carrier it finds and the retry-after off the first `UpstreamThrottleException`, so a carrier a tool wrapped with `wrapWithContext` is still what the server said; the service is the outermost exception's. `summary()` is the one-line form limiters quote in throttle and circuit messages. |

## Rules

- Every status exception renders the same LLM message: `HTTP <status> from <service> at <endpoint>: <body>`.
- The ten mapped statuses and the unmapped one are the eleven implementors of `HttpErrorResponse`, and the only ones. No implementor, and no semantic parent, declares the four accessors itself: a class method would silently shadow the interface default. `HttpErrorResponseTest` holds both.
- Every carrier builds its `HttpErrorDetail` once and returns that same record from `http()`.
- Service quirks are mapped by the client before it calls the factory, onto an existing type. There is no service-specific exception class.
- The dispatcher signals every custom rate limiter of a job with `UpstreamFailure.from(e)` for any `ExternalServiceException` the job throws, so a throttle or a server error stretches the elastic window and can open the circuit.
