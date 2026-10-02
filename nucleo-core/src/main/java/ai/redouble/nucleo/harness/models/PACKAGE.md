# Package: ai.redouble.nucleo.harness.models

Code that names a model breaks in three ordinary ways. The model retires and the name stops
answering. The application moves to a customer whose account reaches the same model through
another provider, under another name. Or a cheaper model turns out to be good enough, and
the name is written in forty places. In each case the fix is an edit to code that had
nothing wrong with it.

Nucleo keeps model names out of code. A job says how much model its work needs, and the
deployment says which model that is. The first half is a **grade**, the second is the
deployment's **catalog** of models, and the part of the runtime that joins them for every
call is the **picker**. This page explains all three, in the order you meet them.

## Grades: how much model the work needs

A grade is a rung on a ladder of capability, from the smallest model to the strongest:
`MICRO`, `SMALL`, `MEDIUM`, `LARGE`, `XL`, `MEGA`. A job declares the lowest rung that can
do its work, as `Grade.SMALL` does in [Hello, model](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/hello/PACKAGE.md)
and in [Your first agent](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/agent/PACKAGE.md).
The grade is a floor: the job may be served by a model of its grade or of any grade above
it, and never by one below.

Which rung a model sits on is decided by its vendor's own tier and its generation, never by
its price: the nano, micro and lite tier is `MICRO`; mini, small, haiku, flash and luna
`SMALL`; medium, sonnet and terra `MEDIUM`; large, and a flagship one generation behind, `LARGE`; the
vendor's current flagship `XL`; and `MEGA` is above the flagship. An open-weight model with
no tier name goes by its parameters, the active ones per token for a mixture of experts. The
discovery applies this rule when it writes an entry
([Discovering an account's models](discovery/PACKAGE.md)), and a person moves a model in
the catalog when it is wrong.

Two things follow from the grade being a floor. A deployment with one strong model runs
every job on it, because a stronger model always qualifies. And a job that asks for more
than the deployment has, say `XL` where the strongest model is `LARGE`, runs on the
strongest there is, with a warning in the log, so code written for the whole ladder runs on
a deployment with a single model.

One more value sits beside the rungs: `Grade.CEILING`, the strongest grade this deployment
serves, whatever that is. A job that always wants the best model available, a chat with a
person waiting on it for example, declares `CEILING`; it runs on `MEGA` where the deployment
can call a `MEGA` model and on `XL` where its strongest is `XL`. `CEILING` is never a grade a
model carries: the runtime works it out from what the deployment can call.

## The catalog: which models exist and what they cost

The catalog is a file, `models.json`, with one entry per model the deployment may call.
An entry is one model on one provider: the same Claude model reached through Anthropic's
API and through Amazon Bedrock is two entries, with two ids, two prices and two sets of
limits, and the same identity. An entry from the Anthropic library's shipped catalog reads:

```json
{ "id": "claude-opus-5-direct", "identity": "opus-5", "grade": "XL", "provider_key": "anthropic-direct",
  "wire_model_id": "claude-opus-5", "max_context_tokens": 1000000, "max_output_tokens": 128000,
  "supports_vision": true, "supports_documents": true, "thinking_mode": "ADAPTIVE",
  "tpm": 2000000, "rpm": 1000, "input_price_per_million": 5.0, "output_price_per_million": 25.0 }
```

- `id` is the name the deployment uses for the entry: in its pins, in the log, on the record
  of every call the entry served.
- `provider_key` names the **provider**, the library that knows how to call this model on
  this service (Anthropic's API here; the provider pages later in this part describe each
  one), and `wire_model_id` is the name that provider sends.
- `grade` is the entry's rung on the ladder.
- `tpm` and `rpm` are the account's tokens and requests per minute on this endpoint, which
  the runtime stays under before it sends anything.
- The prices are per million tokens, in the currency the entry states or inherits from its
  provider's defaults (`USD` here). Nucleo never converts between currencies: costs add up
  per currency, and a spending cap in one currency refuses a call priced in another.
- `supports_vision` and `supports_documents` say whether the model reads pictures and whole
  files such as PDFs.

**Where the file comes from.** Every provider library ships a small catalog of its own: the
current model of each family, with its public facts, list prices and the provider's
entry-tier limits. With nothing else present, the runtime runs on those, with a warning at
start-up naming the file to write. Your account reaches different models at different limits,
so the deployment writes its own file, and a run called the discovery writes it for you by
asking each provider what your credentials can call
([Discovering an account's models](discovery/PACKAGE.md)). When the deployment's file
exists it is the whole catalog, and nothing shipped in a library is read beside it. The
runtime finds it the way logback finds its configuration, the same way on every machine: the
file `-Dnucleo.models` names when it is set, else `models.json` on the classpath. An
application carries it in `src/main/resources`, as it carries `logback.xml`, and the property
is the override.

**Closing an entry.** An entry's `status` says whether it may serve new work: `OPEN` (the
default), `DISABLED` (the deployment's own decision), `DEPRECATED` (the vendor retired the
model), `UNLISTED` (the account no longer lists it) or `UNREACHABLE` (its provider is not
served where this deployment points). A closed entry is never removed: past calls priced
against it still find their price. Kept is all it is: nothing picks a closed entry.

## Pins: the deployment's choice per grade

`pins`, in the same file, is where the deployment says which entries serve each grade, in
its order of preference:

```json
"pins": {
  "SMALL": ["claude-haiku-4-5-direct", "gpt-5-mini"],
  "MEDIUM": ["claude-sonnet-5-direct"],
  "embeddings": "text-embedding-3-small",
  "decision": "kev-local"
}
```

The first id under a grade is the grade's default; the ones after it are what the
deployment wants next when the default cannot serve a request, because its credential is
missing, or because the request sends pictures the default cannot read. An id may name an
entry of a higher grade than the one it serves, never a lower one, and each id appears once
under a grade. The catalog refuses to load when a pin breaks one of these rules, so a
mistyped id fails at start-up and never at the first call. `CEILING` cannot be pinned: it
is derived.

`embeddings` names the one model that turns text into vectors, and `decision` the one
decision model (the third kind of model, in [Decision models](../../tools/deciding/PACKAGE.md)).
Each must name an entry of its own kind, and a `pins` key that is neither a grade nor one
of these two is refused, so a misspelled key also fails at start-up.

## How a request finds its model

When a job is about to run, the picker chooses an entry for each model call it declared.
The shipped picker, `DefaultModelPicker`, reads the pins, and needs no code from you. It
serves a request from the first entry, in this order, that the deployment can call and that
accepts everything the request sends:

1. The grade's pinned entries, in the order written. One that cannot be called is skipped,
   and the log says once which one and why.
2. The grade's other entries, cheapest first by list price. The log says once which entry it
   chose, and that the choice was not the deployment's; pinning it makes the choice
   deliberate.
3. The same two steps at the next grade up, and so on up the ladder.

"Can call" means the entry is open, the provider's library is on the classpath, its
credential is present, a model served only when prompts are shared with the provider has the
Bedrock Mantle project that allows it configured (`ModelSettings.mantleLaxProject`), and the
application's compliance envelope, below, permits it. When
nothing at the grade or above qualifies, the job fails with a `ModelResolutionError` that
names, per provider, the credential that would serve it. A deployment that has pinned
nothing still runs: every grade is served by its cheapest callable entry, or by the grade
above.

**Pictures and documents.** A request that sends images or whole files says so before it
runs, on the `ModelBinding` its requirements return:

```java
ModelBinding binding = wireConversation(req, Depth.IMMEDIATE, this::build);
binding.setSends(Set.of(Input.IMAGES));
```

The picker then serves it only from an entry that accepts pictures (`supports_vision`), or
whole files for `Input.DOCUMENTS` (`supports_documents`). Declaring it is required: a
request whose conversation carries an image it did not declare is refused before it is
sent. The model a request gets is therefore decided by what the code declared, and a
conversation never changes model because of something in its history.

**The checks every answer passes.** Whichever picker answered, the runtime checks the entry
before the call goes out: it is of the kind the request asked for (a chat model, an
embeddings model or a decision model), the compliance envelope permits it, it accepts what
the request sends, it is open, and for a chat model its grade is at least the request's.

**The compliance envelope** is the application's standing rule about which models may see
its data at all. The host sets it once, before the dispatcher starts, with
`JobDispatcher.sealComplianceEnvelope`, and it holds for the life of the process; a
dispatcher started without one uses a fresh `DefaultComplianceEnvelope`.
`DefaultComplianceEnvelope` covers the usual rules: only these provider keys, never these
provider keys, never these models, and whether a model that is served only when prompts
are shared with the provider may be used (`setAllowDataShare`, off by default). When the
envelope refuses the entry a grade would get, the request is served from the nearest grade
the envelope permits, above its own; a `CEILING` request walks down from the strongest.

## Embeddings and decision models

Vectors stored from one embeddings model can be compared only with vectors from the same
model, so the embeddings model is chosen once. The `embeddings` pin names it; without one,
the picker takes the first embeddings entry it can call and warns that every vector stored
from then on is tied to it. Once a process has used an embeddings model it refuses to
switch to another; changing it means migrating the stored vectors.

A decision seat is served by the `decision` pin, or without one by the first decision
entry the deployment can call whose endpoint serves that model, with a warning naming the
pin to write.

## Naming one exact entry

A job may name the entry it runs on: `requireModel(ModelSpec, Depth, ...)` in its
requirements, or `pinModel(spec)` on a model-dependent job. The picker is then skipped, and
every check above still applies except the grade, since a named entry declares none. A named entry has no fallback when it is busy or down, so
this is for the rare job that must run on one model, and for measuring models one against
another, which is what [the benchmark](../../tools/benchmark/PACKAGE.md) does.

## A picker of your own

When pins cannot express the policy, a deployment writes a picker. `AbstractModelPicker` is
the base: a subclass implements `pick(Seat, Situation)`, where the `Seat` is the job class,
its grade and the kind of model, and the `Situation` is everything known about the request
(its depth, what it sends, earlier attempts, the envelope). The base keeps out a model the
deployment's availability probe reports down, and moves new work off an overloaded endpoint onto the same model on
another endpoint; `eligibleForFailover` is where the subclass says which endpoints may stand
in for each other. `OpenModelPicker` lets any endpoint stand in, and the Bedrock library
ships `BedrockOnlyModelPicker` and `ZdrOnlyModelPicker`, which keep substitutes on Bedrock,
and on Bedrock at zero data retention.

The deployment declares its picker once, at start-up, with `ModelPickers.use(picker)`, or
names a class without constructor arguments in `ModelSettings.pickerClass`. The catalog file
and the picker class are the only two places a model id may appear.

## How it works inside

The catalog loader, provider links, per-entry token translations, the resolution gate in
full, availability probes, overload failover and tokenization are in
[Inside the catalog and the picker](HARNESS_MODELS_INTERNALS.md), for those working on the
runtime itself.
