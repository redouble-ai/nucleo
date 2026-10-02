# Inside the exception hierarchy

This page is for people working on the runtime itself: the whole sealed hierarchy, where each
member lives, and the exceptions the runtime throws that are never addressed to a model. The
guide for choosing an exception in a tool is [When a tool fails](EXCEPTIONS.md).

The hierarchy is sealed and built on the `LLMReadable` marker interface of this package; the
[harness document](../PACKAGE.md) describes how the runtime routes its members. The HTTP
status exceptions are detailed in [http/PACKAGE.md](http/PACKAGE.md), the retry signals and
the model-correction failures in [retry/PACKAGE.md](retry/PACKAGE.md). The thinker loop
checks `instanceof LLMReadableException` to recognize the checked and the unchecked branch
alike, and `isCorrectable()` for the verdict.

## The hierarchy

```
LLMReadable (marker interface) - getLLMMessage()

LLMReadableException (sealed, extends LLMReadable)
+-- LLMReadableCheckedException (sealed, checked)
|     +-- CorrectableLLMException (non-sealed abstract) - isCorrectable() = true
|     |     InvalidInputException         - bad input: invalid syntax, blank field, HTTP 400/422
|     |       Http400Exception, Http413Exception, Http422Exception
|     |     ResourceNotFoundException    - well-formed ID that doesn't exist: fetch-by-ID 404
|     |       Http404Exception
|     |     GuardrailException           - policy violation: PII, prohibited content
|     |     JsonParseException           - the model's reply was not the JSON object asked for
|     |     ResponseValidationException  - the reply parsed but left required fields empty
|     +-- UncorrectableLLMException (non-sealed abstract) - isCorrectable() = false
|           ExternalServiceException     - API down, HTTP 500+, network failure
|             |   Http500Exception, Http502Exception, Http503Exception
|             |   HttpUnmappedStatusException - any other error status
|             +-- UpstreamThrottleException      - upstream throttle signal (Retry-After, robot detection, etc.)
|                   Http429Exception
|           UnauthorizedException        - external API rejected credentials: HTTP 401/403
|             Http401Exception, Http403Exception
|           PermissionDeniedException    - internal auth: user lacks access
|           SystemException              - unexpected internal error, bug
|           JobCancelledException        - the dispatcher stopped the job on a cancel
|           JobContext.CancellationException - the signal a running job gets from checkCancellation()
|           JobTimeoutException          - the dispatcher stopped the job on its time budget
|           TokenEstimateExceedsLimitException - the conversation cannot fit the model's window
|           ContextOverflowException     - compaction could not make the conversation fit
|           PromptNotFoundException      - no prompt source holds the key a thinker asked for
+-- LLMReadableRuntimeException (sealed, unchecked)
      +-- CorrectableRuntimeLLMException - correctable, unchecked
      +-- UncorrectableRuntimeLLMException - uncorrectable, unchecked
            QuotaExhaustedException      - the provider account is out of money
            SpendCapExceededException    - admission refused the job under its workflow's spend cap
            ModelNotFoundException       - the catalog holds no model under the id
            ProviderRefusalException     - the provider declined the call; carries model, category, explanation for a caller resubmitting elsewhere
```

Where they live: `GuardrailException` in `ai.redouble.nucleo.guardrails`;
`JobContext.CancellationException` nested in
`ai.redouble.nucleo.harness.JobContext`; `ContextOverflowException` in `harness.conversation`;
`PromptNotFoundException` in `ai.redouble.nucleo.prompt`; `ModelNotFoundException` in
`harness.models`; the `Http` classes in `http`; `JsonParseException`,
`ResponseValidationException`, `TokenEstimateExceedsLimitException` and `QuotaExhaustedException`
in `retry`; every other member in this package. `ErrorsHierarchyContractTest` scans the runtime's
classes and holds this list to be the whole membership.

## Outside the hierarchy

Not every exception the runtime throws is addressed to the model. `DependencyFailedException`
(a dependency of the job failed for good; carries its id), `JobDeadlockException` (a job blocked
on another while holding resources; carries both jobs and the call site) and
`NotImplementedException` (a planned surface that is not built; a message only) are plain
runtime exceptions. The transparent retry signals
(`UpstreamRetryException` and its three subclasses) and the dispatcher's two re-run signals
(`ResponseCorrectionRetryException`, `OutputTruncationRetryException`) are runtime exceptions
the dispatcher consumes; the model never sees them, and `unwrap` and `wrapWithContext` rethrow
any of them found in a cause chain.

## Why the HTTP exceptions share a record and not a class

The ten `HttpNNNException` classes sit under five different semantic parents, because
what the caller should DO about a 400 has nothing to do with what it should do about a
503. That placement is the point of the types, and it is also why they cannot share a
base class - Java allows only one parent.

The payload they all carry therefore lives in a record, `HttpErrorDetail` (status,
service, endpoint, response body), reached through the `HttpErrorResponse` interface
that all ten implement, as does `HttpUnmappedStatusException`, the carrier for any error
status without a class of its own.

## How a throttle reaches the limiters

The dispatcher catches any `ExternalServiceException` from a job with a custom rate
limiter and calls `onRateLimitError(UpstreamFailure)` on that limiter before rethrowing,
so the elastic window stretches and the circuit breaker eventually opens.

`UpstreamFailure` is what the server actually said - status, service, detail, and
any `Retry-After` it asked for - assembled from the exception by
`UpstreamFailure.from`. Limiters record it and quote it back in their throttle,
circuit-transition, and circuit-open messages, so a tool disabled by an open
circuit names the upstream response that disabled it instead of leaving the
reason to be reconstructed from surrounding log lines.
