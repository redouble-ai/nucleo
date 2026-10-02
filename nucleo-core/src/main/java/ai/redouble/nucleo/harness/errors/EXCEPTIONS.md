# LLM-Readable Exception Hierarchy

When a tool fails inside an agent, the model that called it has to decide what to do next,
and it can decide only from what it is told. A stack trace tells it nothing it can act on,
and a `NullPointerException` says even less. The model needs two answers: what went wrong,
in plain words, and whether trying again can help - with a corrected call, or only by
taking another way.

In Nucleo a tool gives both answers by the exception it throws. [Your first
tool](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/tool/PACKAGE.md)
threw `InvalidInputException` for a malformed order number and `ResourceNotFoundException`
for one that does not exist, and [your first
agent](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/agent/PACKAGE.md#when-the-order-number-is-wrong)
showed the model reading the first and calling again with the right number. This page is the
set of exceptions to choose from, and what each one tells the model.

## What the model reads

A failed tool call does not stop the thinker that made it. The failure becomes the result of
that call, marked as an error, and the model reads it on its next turn like any other
result. The text is the exception's message for the model, followed by one line that says
whether retrying can help:

- on a correctable failure, `[This error may be correctable - you can retry with different parameters or try a different approach]`;
- on an uncorrectable one, `[This error is not correctable - consider an alternative approach]`.

A tool that throws an exception outside Nucleo's set gives the model nothing to go on: it
reaches the model as a `SystemException`, which reads `A system error occurred in <component>.
The operation could not be completed.` The details go to the log, and the model learns only
that something broke. Correctable failures are logged as warnings, since they are the
normal back-and-forth of a model correcting itself; uncorrectable ones as errors, with the
stack trace.

Every Nucleo exception carries two messages, one for each reader:

| Method | For | Example (`SystemException`) |
|--------|-----|-----------------------------|
| `getMessage()` | the log and the developer: technical | "System error in Tool instantiation: Failed to create instance of com.foo.MyTool" |
| `getLLMMessage()` | the model: plain and actionable | "A system error occurred in Tool instantiation. The operation could not be completed." |

The unchecked ones described below carry one message: a `CorrectableRuntimeLLMException`
or `UncorrectableRuntimeLLMException` is constructed from its message for the model, and
`getMessage()` returns that same string. `explainToLLM()` is the message together with the
line about retrying, the text the model reads.

## Correctable or not

The first choice is whose problem the failure is.

**Correctable** (`isCorrectable()` is true): the call was wrong, and the model may fix its
arguments and call again.

- `InvalidInputException(parameterName, invalidValue, validationRule)` - the input broke a
  rule: invalid syntax, a blank field. The model reads the parameter, the value and the
  rule, so write the rule as the way to get it right.
- `ResourceNotFoundException(resourceType, identifier)` - a well-formed identifier that
  names nothing, a fetch by id that finds no record. The model is told to try another
  identifier or another tool.
- `GuardrailException` - a guardrail refused the call ([Writing
  guardrails](../../guardrails/PACKAGE.md)).

```java
// the model sent a blank query
throw new InvalidInputException("query", "", "must not be blank");
// the model asked for an article that does not exist
throw new ResourceNotFoundException("PMC article", "PMC12345");
```

**Uncorrectable** (`isCorrectable()` is false): the tool cannot do this right now, whatever
the input, and the model should try something else.

- `ExternalServiceException(serviceName, errorDetails, cause)` - the service is down,
  answered with a server error, or could not be reached.
- `UpstreamThrottleException` - the service is throttling us (below).
- `UnauthorizedException(serviceName, reason)` - the service rejected our credentials.
- `PermissionDeniedException(message)` - the person the work runs for lacks access, by the
  application's own rules.
- `SystemException(component, technicalMessage, cause)` - an unexpected internal error, a
  bug.

```java
// the service never answered; a status that did come back is mapped for you (below)
throw new ExternalServiceException("PubChem", "connection refused", e);
// credentials rejected
throw new UnauthorizedException("ChemSpider", "API key expired");
```

## Search or fetch

Whether "nothing found" is a failure depends on what the tool was asked.

```
SEARCH (query-based, list results):
  API reachable?
    NO  -> ExternalServiceException
    YES -> Results found?
      NO  -> return empty output (VALID RESULT)
      YES -> return populated output

FETCH-BY-ID (single resource by identifier):
  API reachable?
    NO  -> ExternalServiceException
    YES -> Resource exists?
      NO (404)  -> ResourceNotFoundException
      YES       -> return populated output
```

A search that finds nothing has answered the question, and an empty result tells the model
so. A fetch by an identifier that names nothing is the model's mistake to correct.

## Failures from HTTP services

A tool that calls an HTTP service does not choose these exceptions by hand for each
status. The status is mapped onto the same set, by what the model should do about it:

```
400/413/422 -> InvalidInputException          (correctable)
401/403     -> UnauthorizedException          (unless the body says throttle - see below)
404         -> ResourceNotFoundException      (correctable; fetch-by-ID only)
429         -> Http429Exception, an UpstreamThrottleException
500+        -> ExternalServiceException
```

Each mapped status has a class of its own, `Http400Exception` through `Http503Exception`,
placed under the exception whose meaning it has, and each also implements
`HttpErrorResponse`, which carries what the server actually said. That gives two ways to
catch one:

- Catch by the **meaning** when you care what to do next. `InvalidInputException` means the
  model may fix its input and retry; `ExternalServiceException` means it should not.
- Test for **`HttpErrorResponse`** when you care what the server said, without knowing which
  class was thrown.

```java
catch (ExternalServiceException e) {
    if (e instanceof HttpErrorResponse http) {
        log.warn("upstream {} at {}", http.getStatusCode(), http.getEndpoint());
    }
}
```

A status exists only when a response came back. A connection refused or a read timeout is
an `ExternalServiceException` that is not an `HttpErrorResponse` at all, and so tells a
failure to reach the service apart from an answer the service gave.

Never add an exception type for one service. A service's quirk, such as EPO answering
robot detection (`CLIENT.RobotDetected`) with a 403, is mapped by that service's client, in
its `classify` override, onto an existing type. [HTTP status exceptions](http/PACKAGE.md)
has the factory, the classes and the client base that does this mapping.

## Throttling

When a service signals that it is being called too fast, the tool throws
`UpstreamThrottleException`, or its specialization `Http429Exception`, and lets it
propagate. Nucleo does the rest: when a job fails with any `ExternalServiceException` and
has rate limiters of its own attached, the dispatcher tells each of them, so they slow down
and, if the failures continue, stop sending altogether until the service recovers (their
circuit opens). Each limiter records what the server said, the status, the detail and any
`Retry-After` it asked for, and quotes it in its throttle and circuit messages, so a tool
switched off by an open circuit names the response that switched it off.

| Signal | Exception |
|--------|-----------|
| HTTP 429 | `Http429Exception` |
| HTTP 403 with a robot-detection body (EPO `CLIENT.RobotDetected`) | `Http429Exception`, raised by `EPOClient.classify` before the status mapping |
| A throttle header or structured response particular to the API | `UpstreamThrottleException` |
| Server 500/502/503 | Plain `ExternalServiceException` |

Tools throw, the HTTP clients map statuses, the dispatcher signals the limiters. None of
these is caught by the tool itself.

## Wrapping what you call

A tool body calls code that throws other exceptions: a client's `IOException`, a handle's
`ExecutionException`. Two methods turn them into what the model can read.

```java
// wrap anything: an LLM-readable exception in the cause chain is kept, anything else
// becomes a SystemException
try {
    apiClient.call(params);
} catch (IOException e) {
    throw LLMReadableCheckedException.unwrap(e);
}

// a service call made with a parameter the model supplied: name the service, the
// parameter, its value and the step
throw LLMReadableCheckedException.wrapWithContext(e, "PubChem", "cid", cid, "compound fetch");
```

`wrapWithContext` keeps the correctable or uncorrectable verdict of what it wraps and
addresses it to what the model knows. A correctable failure anywhere in the cause chain
comes back as an `InvalidInputException` naming the parameter and its value, with the step
and the buried message as the rule. That is why a 404 on a fetch comes back as an
`InvalidInputException`: the client mapped it to the correctable
`ResourceNotFoundException`, and the wrap readdresses it to the parameter the model sent.
An uncorrectable failure, or one with no LLM-readable exception in it, comes back as an
`ExternalServiceException` naming the service, with the step and the buried message (or the
deepest raw message). Wrap the one service call this way, and keep the outer `unwrap` for
whatever else the body throws ("Service tools" in [Tools, thinkers and
doers](../../tools/PACKAGE.md)).

Neither method swallows the signals Nucleo uses to retry a call on its own: `unwrap` and
`wrapWithContext` rethrow any of them found in the cause chain, so a broad catch never turns
a retry into a failure ([Retry signals](retry/PACKAGE.md)).

## Checked and unchecked

Tools, thinkers and doers throw the checked exceptions above, subclasses of
`CorrectableLLMException` and `UncorrectableLLMException`: `execute` declares
`LLMReadableCheckedException`. Where a checked exception cannot propagate, inside a stream,
a lambda, a callback or enum deserialization, throw `CorrectableRuntimeLLMException` or
`UncorrectableRuntimeLLMException`. The model reads both kinds the same way.

```java
// checked - normal tool code
public MyOutput execute(JobResources resources, JobContext context)
        throws LLMReadableCheckedException {
    throw new InvalidInputException("query", value, "must not be blank");
}

// unchecked - inside a stream
results.stream().map(r -> {
    if (r.isInvalid()) {
        throw new CorrectableRuntimeLLMException("Invalid result: " + r.getId());
    }
    return transform(r);
});
```

Code with no `throws` clause turns a caught exception into an unchecked one with
`unwrapRuntime`:

```java
throw LLMReadableRuntimeException.unwrapRuntime(e, "PubChem search failed");
// an unchecked LLM-readable exception in the chain -> returned as itself
// a checked one -> the matching unchecked class with the same message, correctable stays
//   correctable
// none -> UncorrectableRuntimeLLMException with the fallback message
```

`unwrapRuntime` does not rethrow the retry signals, because the code it serves is not on
the dispatcher's retry path and never meets one.

## Failures the runtime raises

Some exceptions of this set are thrown by Nucleo itself, and code that submits jobs may
catch them: a job stopped by a cancel (`JobCancelledException`) or by its time budget
(`JobTimeoutException`); a conversation that does not fit the model's window even after
compaction (`ContextOverflowException`); a prompt key no source holds
(`PromptNotFoundException`); a model id the catalog does not hold
(`ModelNotFoundException`); a provider account out of money (`QuotaExhaustedException`); a
job refused under its workflow's spend cap (`SpendCapExceededException`); and a provider
that declined the call (`ProviderRefusalException`), which carries the model, the category
and the provider's explanation for a caller that resubmits elsewhere. All of them are
uncorrectable.

## How it works inside

The whole hierarchy, where each member lives, and the exceptions that are addressed to the
runtime and never to the model are in [Inside the exception
hierarchy](HARNESS_ERRORS_INTERNALS.md), for those working on the runtime itself.

> **Example:** [Your first agent, when the order number is wrong](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/agent/PACKAGE.md#when-the-order-number-is-wrong) -
> a tool refuses a malformed number with `InvalidInputException`, and the model corrects
> its call.
