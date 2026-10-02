# Package: ai.redouble.nucleo.harness.errors.http

A tool that calls an HTTP service gets back a status, and what a failure status means for
the model depends on its number. A 400 says the request was wrong and the model can fix it;
a 503 says the service cannot serve anyone right now, so the model should take another way;
a 429 says slow down. This package turns a status into the exception that says so to the
model ([When a tool fails](../EXCEPTIONS.md)), and keeps on it what the server actually
answered.

## From a status to an exception

`HttpExceptions.throwIfError(service, endpoint, statusCode, responseBody)` is the gate at the
bottom of a client method: it returns for any status below 400 and throws the matching
exception from 400 up. `HttpExceptions.fromStatus` builds the same exception without
throwing it. A client built on `AbstractApiClient` ([The HTTP connection
pool](../../../http/PACKAGE.md)) needs neither: it maps every status from 300 up this way,
with the response body quoted whole, and reports a service it could not reach, or a
successful body its parser refused, as an `ExternalServiceException` with no status.

| Status | Class | Its meaning | For the model |
|---|---|---|---|
| 400, 413, 422 | `Http400Exception`, `Http413Exception`, `Http422Exception` | `InvalidInputException` | correctable: the request was wrong |
| 404 | `Http404Exception` | `ResourceNotFoundException` | correctable: the identifier names nothing |
| 401, 403 | `Http401Exception`, `Http403Exception` | `UnauthorizedException` | uncorrectable: the credentials were refused |
| 429 | `Http429Exception` | `UpstreamThrottleException` | uncorrectable, and the job's rate limiters slow down |
| 500, 502, 503 | `Http500Exception`, `Http502Exception`, `Http503Exception` | `ExternalServiceException` | uncorrectable: the service failed |
| any other (402, 408, 418, 520) | `HttpUnmappedStatusException` | `ExternalServiceException` | uncorrectable: the service failed |

Every one of them reads to the model the same way, `HTTP <status> from <service> at
<endpoint>: <body>`, followed by the line that says whether retrying can help.

## What the server said

Every exception raised from a status implements `HttpErrorResponse`, so code can read the
server's answer without knowing which class was thrown: `getStatusCode()`, `getService()`,
`getEndpoint()` and `getResponseBody()`, or `http()` for all four as one `HttpErrorDetail`
record. An exception that is not an `HttpErrorResponse` never got an answer: only a failure
to reach the service is without a status.

The endpoint is the path alone. A query string is cut when the record is built, because it
carries the caller's own arguments, a search term or an identifier, and an error message
never hands those back.

## A service's own signals

Some services say more than their status does. Such quirks are mapped by the service's
client before the status mapping, onto an existing type, and never with an exception class
of their own. A client built on `AbstractApiClient` overrides `classify` to return the
exception a response deserves, and `postProcessResponse` to watch every response for signals
that are not errors themselves. EPO answers robot detection with a 403 whose body names
`CLIENT.RobotDetected`, and its client raises that as an `Http429Exception`. A service that
throttles through something other than a 429 status, a throttle header or a structured
body, is answered with an `UpstreamThrottleException`, which carries the seconds the server
asked for as advisory `getRetryAfterSeconds()`, 0 when it gave none.

The factory sees the status and the body only, so an `Http429Exception` it builds asks for 0
seconds. A client that read a `Retry-After` header constructs the exception itself with the
seconds the server asked for:
`new Http429Exception(service, endpoint, responseBody, retryAfterSeconds)`.

## What the rate limiters record

For any `ExternalServiceException` a job throws, the dispatcher signals every custom rate
limiter attached to the job, so a throttle or a server error stretches the limiter's window
and, when the failures continue, opens its circuit. What each limiter records is an
`UpstreamFailure`: the status (0 when no response came), the service, the server's detail
and the seconds a `Retry-After` asked for (0 without one). `UpstreamFailure.from` finds them
anywhere in the cause chain, so a status exception a tool wrapped with `wrapWithContext` is
still what the server said. `summary()` is the one line limiters quote in their throttle and
circuit messages.

## How it works inside

How each class fills its semantic parent, and the rules that keep the eleven status
exceptions and their shared record consistent, are in [Inside HTTP status
exceptions](HARNESS_ERRORS_HTTP_INTERNALS.md), for those working on the runtime itself.
