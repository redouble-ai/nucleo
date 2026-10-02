# Inside retry events

This page is for people working on the runtime itself: what the retry events have in common,
the exact messages and titles they render, the test that holds them, and where the dispatcher
builds each one. To show retries to a person, read [the guide](PACKAGE.md) first.

## The category

`RetryEvent` is sealed to four members. Every member is `HumanReadable`, carries the job's
snapshot, renders its message from what it carries, answers `STATUS_UPDATE` as its `MsgType`
rather than a lifecycle phase, and names itself in its title.

| Event | Published for | Carries | Message |
|---|---|---|---|
| `RateLimitRetryEvent` | a 429, the framework's own budget | the limit type, the model, the attempt, the system throttle and the estimated delay | `Hit acceleration limit (system throttle: <1+throttle>x) for <model>. Retrying in ~<s> seconds (attempt <n>)` for an acceleration limit; `Hit capacity limit for <model>. ...` for any other kind |
| `TransientErrorRetryEvent` | a transient server error, a plain 5xx or a 529 overload; the error details name which | the provider, its words, the attempt and the estimated delay | `Server error from <provider>: <details>. Retrying in ~<s> seconds (attempt <n>)` |
| `OutputTruncationRetryEvent` | a reply stopped at `max_tokens`, re-run with a larger budget | the model, the previous budget and the new one | `Output truncated at <previous> tokens on <model>. Retrying with budget <new>.` |
| `ResponseCorrectionRetryEvent` | a reply that failed to parse or validate, re-run with a correction appended | the model, the correction attempt and the failure's class name | `Response on <model> failed to parse or validate (<failure>). Retrying with correction, attempt <n>.` |

An estimated delay that is not known reads as zero seconds. The titles are `Rate Limit Retry`,
`Server Error Retry`, `Output Truncation Retry` and `Response Correction Retry`.

`RetryEventContractTest` holds every row of this table as the events render it.

## Which signal becomes which event

The dispatcher's retry loop (`JobDispatcher.executeWithRetry`) maps the signals of
`harness.errors.retry` to these events: `RateLimitRetryException` to `RateLimitRetryEvent`
(the `case RateLimitRetryException` arm), `OverloadRetryException` and
`TransientErrorRetryException` both to `TransientErrorRetryEvent` (their two arms, the
provider and its words carried through), `OutputTruncationRetryException` to
`OutputTruncationRetryEvent` (the escalation branch), and `ResponseCorrectionRetryException`
to `ResponseCorrectionRetryEvent` (the correction branch). The mapping is the dispatcher's;
the events carry what the arm hands them.
