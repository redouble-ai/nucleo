# Inside retry signals

This page is for people working on the runtime itself: what each retry signal carries, the
pacing and throttle numbers the dispatcher reads from it, and the dispatcher's own re-run
signals. The guide is [Retry signals](PACKAGE.md). The hierarchy the model-facing
exceptions belong to is documented in [EXCEPTIONS.md](../EXCEPTIONS.md); the dispatcher's
use of the signals is documented in [harness/llm/PACKAGE.md](../../llm/PACKAGE.md).

## Transparent retry signals

`UpstreamRetryException` is sealed to three signals. None is LLM-readable: the model never sees
them. The dispatcher has one backoff loop for all three and reads what varies from the signal
itself. `LLMReadableCheckedException.unwrap` and `wrapWithContext` rethrow any of them found in a
cause chain, so a broad catch never turns one into a terminal failure.

| Signal | Meaning | Jitter window | Throttle increments |
|---|---|---|---|
| `RateLimitRetryException` | 429: this caller nudged past its own budget. Carries the `RateLimitInfo` the headers supplied, the attempt number, the provider's message and its suggested delay; `withIncrementedAttempt()` re-issues it with the counter advanced and everything else kept. | 5 s to 30 s | 1 |
| `OverloadRetryException` | 529: the provider's fleet is saturated regardless of budgets. Carries the provider and its words. | 15 s to 90 s | 3 |
| `TransientErrorRetryException` | A plain 5xx or a connection failure: a fault, not a slow-down request. Carries provider, detail, status and attempt; `withIncrementedAttempt()` as above. | 5 s to 30 s | 0 |

The jitter window is the base the dispatcher scales by the attempt: both edges are
multiplied by the attempt number (at most ten), and the upper edge is capped at five
minutes. The throttle increments are what the provider client feeds the model's admission
limiter (`TokenBucketRateLimiter.recordBackpressure`); zero means the limiter does not hear
about the signal.

How many re-runs any of them may claim per job execution, on the dispatcher's one shared
counter, is the job's own: `Job.getUpstreamRetries()`, `Job.DEFAULT_UPSTREAM_RETRIES` (three)
unless the job's constructor or its caller set another through `AbstractJob.setUpstreamRetries`;
a thinker hands its budget to every model call it makes, an `LLMCall` and a decision
thinker's `DecisionCall` alike. The pacing window scales by attempt, so
every re-run past the first is minutes of wall time a caller waits behind, and a fault still
there after a few attempts is an outage or a request the endpoint cannot serve, which more
waiting never fixes. Past the budget the job fails with `UncorrectableRuntimeLLMException`
naming the budget, the last signal as its cause.

`UpstreamRetryException.details(Throwable)` renders a failure for a log line or a persisted
row: the message, then the root cause in brackets when it is a distinct exception, the class
name alone when there is no message anywhere.

## The dispatcher's own re-run signals

Two more runtime exceptions, neither LLM-readable, neither an `UpstreamRetryException`; both are
rethrown by `unwrap` and `wrapWithContext` the same way.

- `ResponseCorrectionRetryException` - the model's reply failed to parse or validate, a correction
  has been appended to the conversation, and the job is re-run so the model can fix it. Carries
  the model, the correction attempt, and the correctable failure as its cause.
- `OutputTruncationRetryException` - the provider stopped at `max_tokens` and the job is re-run
  with a larger output budget. Carries the model, the previous budget and the new one.
