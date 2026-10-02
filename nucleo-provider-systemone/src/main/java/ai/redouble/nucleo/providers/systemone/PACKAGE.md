# ai.redouble.nucleo.providers.systemone

This provider calls decision models ([What a decision model is](../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/tools/deciding/PACKAGE.md)):
TypeSafe's hosted Jev, and the open models that answer in the same shape, such as Jared
Palmer's Kev (`github.com/jaredpalmer/kev`, Apache-2.0), run on a machine the deployment
owns. They all speak one wire, TypeSafe's System One (`POST /v1/systemone`), so one client
serves them all; which model answers depends on where the credential points and which
catalog entry the deployment pins. The library is `nucleo-provider-systemone`.

## The credentials

A decision model is reached one of two ways, and each has a provider and a credential of
its own:

- **An endpoint behind a key**, provider `systemone-decision`, credential
  `systemone-api-key`: the bearer token in `SYSTEMONE_API_KEY` and the API root in
  `SYSTEMONE_API_KEY_HOST`. The root is `https://api.typesafe.ai` for Jev, or the HTTPS
  endpoint `kev-deploy` prints for a Kev on Modal.
- **A server on this machine**, provider `systemone-local-decision`, credential
  `systemone-local`: the address alone in `SYSTEMONE_LOCAL_HOST`, `http://127.0.0.1:8009`
  for a Kev started as its README starts it. A local server checks no key, so none is asked
  for and none is sent.

A root must carry its scheme, `https://` or `http://`; one without is refused, with the
accepted form named.
[Set up and run the demo](../../../../../../../../../AGENTS.md) says how a credential reaches
the runtime.

## What it serves

The library ships three catalog entries:

- `jev-1.13.0`, TypeSafe's hosted model, at its published limits (250,000 input tokens per
  second, 1,200 requests per minute) and price ($0.042 per million input tokens, output
  free), bounded by that window like every hosted model.
- `kev`, Kev behind a key, and `kev-local`, Kev on this machine. Whichever size of Kev the
  server loaded answers under the one name `kev-latest`. Each is bounded by
  `max_concurrent: 1`, one request at a time, because a Mac server computes one batch at a
  time; a GPU server batches, and a deployment raises the number. Each reads a state of up
  to 16,384 tokens and costs zero: local compute is unmetered, and a rented GPU bills by the
  hour.

A deployment names the one it runs under `"pins": {"decision": "<id>"}`. Without a pin,
the first decision entry the deployment can call whose endpoint serves it answers, with a
warning in the log.

On connecting, the provider reads the endpoint's own model listing (`GET /v1/models`). The
listing verifies the credential, tells the discovery what the endpoint serves, and says
which model the server loaded, on what device and at what precision, as in
`model: jaredpalmer/kev-9b on mps, bfloat16`. It also keeps the Jev entry from being sent to
a Kev server, and the Kev entries from being sent to TypeSafe.

A failure reads by the status the endpoint answered: a rate limit waits for the endpoint's
`retry-after`, a server error or no answer at all is retried at a measured pace, any other
refusal fails naming the status, and a reply that is not an answer fails naming what was
missing. Neither the key nor the state appears in any failure's message.

## How it works inside

The classes, how the listing answers what an endpoint serves, the failure classification
and the tests are in [Inside the System One provider](PROVIDERS_SYSTEMONE_INTERNALS.md),
for those working on the runtime itself.

> **Example:** [One decision](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/decision/PACKAGE.md) -
> the local connection in one environment variable, and a ticket triaged on it.
