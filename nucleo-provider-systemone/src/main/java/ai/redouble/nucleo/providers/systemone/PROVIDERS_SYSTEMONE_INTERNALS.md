# Inside the System One provider

This page is for people working on the runtime itself. What the provider connects to, its
two credentials and its shipped entries are in [the guide](PACKAGE.md); this page holds its
classes, how it reads an endpoint's listing, how it classifies failures, and its tests.

| Class | Role |
|-------|------|
| `SystemOneProvider` | Key `systemone-decision`, platform `systemone`: the TypeSafe-compatible connection, an endpoint behind a bearer key. The key ends in `-decision`, which is how `ModelSpec.isDecision()` identifies the family; a decision seat resolves only to an entry of a decision provider. |
| `LocalSystemOneProvider` | Key `systemone-local-decision`, the same platform and wire: a server on this machine, reached by its address alone and called with no key. |
| `SystemOneClient` | The `AbstractDecisionClient` over the endpoint: reads its API root, and its key when the credential has one, from the credential its provider owns; encodes the request with the runtime's `SystemOneWire`, posts it, reads the answers back with the same codec, and keeps the `x-typesafe-request-id` header an endpoint may answer with. |
| `SystemOneEndpoint` | The transport, an `AbstractApiClient`: a bearer key on every request when the credential carries one, a 429 classified with its `retry-after`, every other status typed by the framework's status mapping. |

## The listing

`SystemOneProvider` is a `ModelDiscovery`: `GET /v1/models` (`SystemOneModelListing`) is the
discovery listing, so a connect verifies the credential against the server and a discovery
writes what the server serves (one run once: an endpoint that names the run behind each
served name, as Kev does with `kev-latest` and `jev-latest`, is listed under the first name
per run, so the same weights never become two entries to price and pin); it is the
provider's `connectionFacts()`, the endpoint and one line per run however many names it
answers under, saying which model it loaded, on what device, at what precision (`model:
jaredpalmer/kev-9b on mps, bfloat16`, from the fields Kev reports; an endpoint that reports
less shows less; the catalog note the discovery writes keeps the rest: the endpoint's own
description, or, when it gives none, the full line with the backend and the calibration
temperature); and it is `serves(wireModelId)`, true for the names the endpoint lists, so a
catalog carrying both the Jev and the Kev entry aims each only at an endpoint that serves it
(`DefaultModelPicker` asks it before serving an unpinned decision entry, discovery drops an
unserved entry as it does on every platform). The listing is fetched once per host and kept;
every `listModels()` refreshes it. A listing that could not be read is kept as its failure
message: the facts show it, and `serves` answers true so a transient failure never drops an
entry, the ping being the judge. With no credential connected there is no endpoint to ask:
the facts are empty and `serves` answers true. `SystemOneModelListingTest` pins the parse
and the three answers against a recorded Kev listing, an endpoint that does not list, and
no credential at all.

## How a failure reads

By the status the endpoint answered, never by the words of the request: a 429 is a rate
limit with whatever `retry-after` it carried, a 5xx or no answer at all is a server error
the dispatcher paces, a 4xx that is not a 429 is a failure naming the status, and a 2xx
whose body is not an answer is a service failure naming what was missing. The bearer token
and the state never appear in any message of the chain (`SystemOneClientTest`).

`DecisionLoadTest` runs the whole harness against a local server bounded by one request in
flight: two hundred decisions submitted at once all complete and the server never sees two
at a time; a server that dies mid-run fails the rest by name and none hangs; a server back on
the same port serves the next batch. `LiveSystemOneTest` runs one decision through the
dispatcher against a real server named by `-Dsystemone.live.url`, and is skipped without it.
`LiveDecisionThinkerTest` runs a whole `DecisionThinker` loop against the same server: the
model splits the minutes, drafts the notice from the one statement that changes a price,
finishes, and selects that notice as the answer.
