# Package: ai.redouble.nucleo.events.retry

Some failures of a model call go away when the call is made again: the provider says the
caller is sending too much, its servers fail or are overloaded, the reply stops at its output
budget, or the reply does not fit the answer class you declared. The dispatcher handles these
itself and runs the job again; [Retry signals](../../harness/errors/retry/PACKAGE.md) says
which failures qualify and how long it waits before each new try. Your code gets its result
as if nothing had happened, only later. A person watching the run, though, sees nothing
happen for seconds or minutes.

A retry event tells them why. Each time the dispatcher decides to run a job again instead of
failing it, it publishes one of the four events of this package on the job's behalf. They
form the `RetryEvent` family, so a chat window or a job console subscribes to `RetryEvent`
once and receives all four.

## The four events

| Event | Published when | What it carries |
|---|---|---|
| `RateLimitRetryEvent` | The provider answered 429: this caller went past its rate limit. | The kind of limit, the model, the attempt number, how far Nucleo has slowed its own sending to that model, and the estimated wait. |
| `TransientErrorRetryEvent` | The provider failed on its side: a plain 5xx or a 529 overload. The provider's own words say which. | The endpoint that failed (for a model call, the model's id), its words, the attempt number and the estimated wait. |
| `OutputTruncationRetryEvent` | The reply stopped at its output budget, and the job runs again with a larger one. | The model, the budget that was too small and the new one. |
| `ResponseCorrectionRetryEvent` | The reply did not parse into the declared answer or failed its checks ([Answers as Java objects](../../harness/schema/PACKAGE.md)), and the job runs again with a correction added to the conversation. | The model, the number of the correction and the class name of the failure. |

## Showing them to a person

Every retry event is written for a person to read. Its message, the same from `message()`
and from `getHumanMessage()`, is a sentence ready to show, such as
`Output truncated at 4096 tokens on <model>. Retrying with budget 8192.` Its `title()` names the kind of retry, and
its `msgType()` is `STATUS_UPDATE`, the type of a passing status line. Each carries the
snapshot of the job being retried, so an observer of one workflow receives the retries of that
workflow's jobs:

```java
dispatcher.subscribe(retry -> System.out.println(retry.message()), RetryEvent.class);
```

The event logger writes retries at DEBUG, under the `retry` kind
([Logs, traces, metrics and cost](../../harness/observability/PACKAGE.md)).

## How it works inside

The exact messages and titles, the test that holds them, and where in the dispatcher each
event is built are in [Inside retry events](EVENTS_RETRY_INTERNALS.md), for those working on
the runtime itself.
