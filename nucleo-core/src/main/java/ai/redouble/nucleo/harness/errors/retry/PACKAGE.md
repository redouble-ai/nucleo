# Package: ai.redouble.nucleo.harness.errors.retry

Some failures of a call to a model are nobody's mistake. The provider says this caller is
going too fast, or that its whole fleet is overloaded, or a server error or a dropped
connection gets in the way. Waiting and sending the same request again is what fixes them,
so Nucleo does it itself: the model never hears about these failures, and your code does not
handle them. Other failures are the model's own reply being unusable, and there Nucleo tells
the model what to fix and asks again. A few failures no retry can fix, and those fail at
once. This page covers each group and the one number you set.

The exceptions a tool throws to tell a model what went wrong are in [When a tool
fails](../EXCEPTIONS.md); everything here happens beneath them.

## Waiting out the provider

A provider client raises one of three signals, the subclasses of `UpstreamRetryException`,
and the dispatcher answers each by waiting and running the job again:

- `RateLimitRetryException` - a 429: this caller went past its own budget.
- `OverloadRetryException` - a 529: the provider's fleet is saturated, whoever is calling.
- `TransientErrorRetryException` - a plain 5xx or a connection failure: a fault, with no
  request to slow down.

The wait is random within a window that grows with every attempt, and an overload waits in
a wider window than the other two. A rate limit and an overload also slow down every job
calling that model, since the whole fleet is about to hear the same thing; a transient
fault does not, because cutting everyone's rate over a stray 500 would be an overreaction.

## How many times

The number of re-runs is the job's own: `Job.getUpstreamRetries()`,
`Job.DEFAULT_UPSTREAM_RETRIES` (three) unless the job's constructor or its caller sets
another through `AbstractJob.setUpstreamRetries`. All three signals draw on one counter per
execution of the job, separate from any other retries the job has. A thinker hands its own
number to every model call it makes.

Choose it by who is waiting. The window grows with each attempt, so every re-run past the
first is minutes a caller waits behind, and a fault still there after a few attempts is an
outage or a request the endpoint cannot serve, which more waiting never fixes. A job whose
caller waits interactively sets fewer; an unattended pipeline may set more. Past the number,
the job fails with an `UncorrectableRuntimeLLMException` naming it, the last signal as its
cause.

None of the signals is addressed to a model, and none should be caught. A tool that catches
broadly around a model call passes what it caught through `LLMReadableCheckedException.unwrap`
or `wrapWithContext`, which rethrow any of these signals found in the cause chain, so a wait
the dispatcher knows how to do is never turned into a failure.

## When the model's reply is unusable

Two failures are the model's to fix, and both are correctable:

- `JsonParseException` - the reply was not the JSON object asked for. The model is told the
  shape it missed and the parser's reason.
- `ResponseValidationException` - the reply parsed but left fields marked `@LLMRequired`
  empty ([Answers as Java objects](../../schema/PACKAGE.md)). The model is told every field
  that failed, one per line.

Nucleo appends that message to the conversation as a correction and runs the call again, so
the model answers with its mistake in front of it; the signal that asks the dispatcher for
the re-run is `ResponseCorrectionRetryException`. A job gets two corrections
(`ResponseCorrection.MAX_CORRECTIONS`). After them, a reply that still does not parse fails
with `JsonParseException`, and one that still leaves required fields empty is returned as it
is, with a warning in the log.

A reply the provider cut off at its output limit is run again once with a larger output
budget, asked for by `OutputTruncationRetryException`. A reply cut off again fails.

## What no retry fixes

- `QuotaExhaustedException` - the provider account is out of money. It is never retried,
  because waiting cannot improve it; the fix is to fund the account or raise its spend
  budget. The message names the provider, the account, the model when known, and the
  provider's own words when it gave any.
- `TokenEstimateExceedsLimitException` - before sending, Nucleo estimated that the
  conversation does not fit the model's window. It carries the estimate, the limit, and the
  overflow as an amount and a ratio. A thinker answers it by compacting the conversation and
  trying again, and fails with `ContextOverflowException` when even that does not fit
  ([Compaction](../../conversation/compaction/PACKAGE.md)).

## How it works inside

The waiting windows and throttle increments of each signal, what each carries, and how the
dispatcher paces its re-runs are in [Inside retry signals](HARNESS_ERRORS_RETRY_INTERNALS.md),
for those working on the runtime itself. The dispatcher's side is in [Model clients and rate
limits](../../llm/PACKAGE.md).
