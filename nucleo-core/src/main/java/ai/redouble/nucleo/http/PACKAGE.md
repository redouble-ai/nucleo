# Package: ai.redouble.nucleo.http

A tool that looks something up on the web or calls a service's API needs an HTTP connection.
Opening a fresh client for every call wastes sockets and time, and letting every agent call
at once lets a burst of agents open more connections than the process can use. Nucleo keeps
one shared HTTP connection pool for the whole process, and admission counts the jobs that use
it ([Admission](../harness/admission/PACKAGE.md)), so a job gets its place in the pool
together with everything else it needs and never holds one while waiting for something else.

This page shows how a tool makes HTTP calls, how to write a client for a service, and what
the pool does.

## Calling a service from a tool

A tool that makes HTTP calls says so in its requirements, and uses the client
`JobResources` hands it:

```java
@Override
public JobRequirements getRequirements() {
    JobRequirements requirements = new JobRequirements();
    requirements.setRequiresHttpConnection(true);
    return requirements;
}
```

```java
CloseableHttpClient client = HttpConnectionPools.getInstance().getClient();   // standalone code
CloseableHttpClient client = resources.getHttpClient();                       // inside a job
HttpReply reply = client.execute(new HttpGet("https://api.example.com/endpoint"), HttpReply.reader());
if (reply.status() != 200) { ... }
```

`setRequiresHttpConnection(true)` puts one place in the pool into the job's demand, granted
with everything else before `execute` runs and returned when the job ends. A job that uses a
model or embeddings gets the place without asking, and so does a job that declares a limit
needing HTTP, such as a web service's rate window. `resources.getHttpClient()` returns the
shared client in every case; the declaration is what makes admission count the job.

Pass the client a response handler, as above. The client hands the response to the handler
and closes it as soon as the handler returns, so the connection is back in the pool before
your code looks at the status. `HttpReply.reader()` is the handler that reads the whole
response into an `HttpReply`: `status()`, `headers()`, `body()` as text (empty for a status
that carries no body, such as 204 or 304), and `header(name)` for the first header of that
name, matched case-insensitively, or null.

## Writing a client for a service

When several tools talk to one service, give the service a client of its own by extending
`AbstractApiClient`. The subclass says what the service is called and where it lives, and
builds its typed methods on the request methods of the base class:

```java
public class GeocoderClient extends AbstractApiClient {
    private final String apiKey;

    public GeocoderClient(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected String getServiceName() {
        return "geocoder";
    }

    @Override
    protected String getBaseUrl() {
        return "https://geocoder.example.com/v1";
    }

    @Override
    protected void decorateRequest(HttpUriRequestBase request) {
        request.setHeader("Authorization", "Bearer " + apiKey);
    }

    public JsonNode lookup(String address) throws LLMReadableCheckedException {
        return get("/lookup?q=" + encode(address));
    }
}
```

`getServiceName()` is the name every failure and log line carries, and `getBaseUrl()` the
prefix every path is appended to. `encode(value)` URL-encodes a query value as UTF-8. The
request methods:

| Method | Sends | Returns |
|---|---|---|
| `get(path)` | GET, `Accept: application/json` | the parsed JSON tree |
| `getText(path, acceptType)` | GET with the caller's `Accept` | the raw body |
| `post(endpoint, body, type)` | POST, the body written as JSON | the response read as `type` |
| `postForJson(endpoint, body)` | POST, the body written as JSON | the parsed JSON tree |
| `postForReply(endpoint, jsonBody)` | POST, the JSON as written, `Accept: application/json` | the whole `HttpReply`, for a client that reads account facts off the response headers |

For any other request, `executeRequest(request, endpoint, parser)` runs it the same way and
hands a successful body to a `ResponseParser`, the function that turns the body into a value.

The client uses the shared pool unless another client is injected with `setHttpClient`.

### Adapting to the service

Every request goes through the same steps, and each step is a method a subclass may
override:

1. `buildUrl(path)` makes the URL, by default `getBaseUrl() + path`. Override it for a
   service that wants a query parameter, such as an API key, on every request.
2. `decorateRequest(request)` adds headers, by default none. Authentication goes here.
3. The request is sent and the response read whole.
4. `postProcessResponse(status, headers, body)` sees every response, whatever its status. It
   is the place to pick up signals that are no errors, such as a throttling header.
5. `classify(status, headers, body)` may turn a response into a typed failure of your
   choosing, for a service whose status alone does not say what happened; a 403 whose body
   says the caller was taken for a robot can become a rate-limit failure, so the service's
   limiter slows down. Its answer, when not null, replaces the usual mapping.
   `classify(endpoint, status, headers, body)` is the same hook with the endpoint, for a
   message that must say where it failed; by default it calls the other.
6. Otherwise any status from 300 up becomes a failure, below.

### Failures

Every failure is typed, so a tool and the model reading its failure can tell what went wrong
([HTTP status exceptions](../harness/errors/http/PACKAGE.md)). A status from 300 up is an
`HttpErrorResponse` carrying the status, the service, the endpoint and the service's own
answer: 400, 401, 403, 404, 413, 422, 429, 500, 502 and 503 each have a class of their own,
and any other status is an `HttpUnmappedStatusException`. A 3xx arrives only when the request
turned redirects off, since the client follows them otherwise. A service that could not be
reached at all, and a successful body the parser could not read, are each an
`ExternalServiceException` with no status. A failure names the endpoint by its path alone,
with the query cut off, and carries nothing else of the request: no header, no body.

## The pool

One pool holds `HttpSettings.poolSize` connections, 1000 unless the deployment sets another
number, as the total and as the limit per host alike, and the admission gate has as many
places as the pool has connections. Its timeouts: 30 seconds to open a connection, 15 minutes
for a response and for each socket read (a model writing a long answer is silent until it is
done), and 60 minutes to wait for a free connection when all are in use. A connection idle
for more than 5 seconds is checked before it is reused, and one idle for 5 minutes is closed.
`getStats()` reports the pool's live numbers.

The pool never retries a request on its own. A 429 or a 503 comes straight back to the
client and the service sees the request exactly once. The client then turns the answer into
a typed failure: a model client into a retry signal, which the dispatcher acts on after the
job has given back everything it holds ([Retry signals](../harness/errors/retry/PACKAGE.md)),
any other client into an `Http429Exception` or `Http503Exception`, which fails the job and
slows down the rate limits it declared. Apache's default would resend the request inside
the call, after sleeping as long as the retry-after header asks, while the job still held
its place and its connection.

The pool is the transport of every client built on `AbstractApiClient`, several of the model
providers among them, and of every tool that takes `resources.getHttpClient()`. A provider
built on a vendor's SDK uses that SDK's own transport; the connect and response timeouts are
public, as `HttpConnectionPools.CONNECT_TIMEOUT_SECONDS` and `RESPONSE_TIMEOUT_MINUTES`, so
such a provider can give its calls the same window instead of the SDK's shorter defaults.
Either way the gate counts the job: its capacity is a ceiling on admitted HTTP-using jobs,
whichever transport they use. Code that is not a job can still use the pool, and then waits
for a connection under the 60-minute limit alone.
