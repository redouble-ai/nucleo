# Package: ai.redouble.nucleo.harness.models.discovery

The catalog that ships inside each provider library is a starting point: the current model
of each family, at the list price and the entry-tier limits the provider publishes. Your
account is different. It may not have access to some of those models, it has models the
libraries never heard of, and its limits are its own. A catalog that does not match the
account fails in quiet ways: a grade served by a model the account cannot call, a rate
limit far below or above the one the provider enforces.

The discovery writes the deployment's catalog from the account itself. It asks each
provider the credentials reach what the account can call, sends one tiny request to every
model it has not seen before, and writes `models.json` with what it learned: which models
answer, with the limits their responses carried, and a report of everything it could not
settle. It runs when you set a deployment up and again when something changes, a new
credential, a model granted in a console, a new model released; it never runs while your
application serves work.

## Running it

`CatalogDiscovery` is the discovery. Its `main` runs it once from a classpath that carries
the provider libraries and sees the credentials, on a dispatcher of its own since every probe
is a job, prints the report and writes the file; its
arguments are `--out <path>` to write somewhere else, `--report-only` to read the report
without writing, and `--include-older` to keep, on a first run, the older versions of a
model the account also has newer. From code, `CatalogDiscovery.run(includeOlder)` returns the result, catalog and
report, without writing; `run(includeOlder, scope)` rediscovers only the providers named in
`scope` and carries every other provider's entries through unchanged, for a host that
discovers as credentials arrive. The demo runs it from its page and as its `discover`
command; [Set up and run the demo](../../../../../../../../../../AGENTS.md), step 3, walks
through a first run and every section of the report.

The file goes where you name it: `--out <path>`, else the file `-Dnucleo.models` names. The
runtime reads its catalog off the classpath, so an application's catalog is written into its
`src/main/resources/models.json`, and the next build carries it. The result is loaded and
validated before it is written, so a file the discovery writes always loads.

## What it keeps of your file

The deployment's file is the deployment's, and the discovery treats it that way:

- **Nothing you wrote is re-checked or rewritten, except a price the provider publishes and
  a thinking mode the model refuses.**
  An entry already in the file keeps its facts exactly as they are and is not pinged again.
  Your pins are kept and never moved. The thinking mode is described below. The other
  exception is a price: on Bedrock the discovery
  reads the AWS price list, the public files AWS publishes per region, and every entry it
  prices takes the listed rates, the regional ones for a `us.` or `eu.` profile and the global
  ones for a `global.` profile, because that is what the account is billed and a typed price
  goes stale. The report shows each such entry's rates before and after. An entry the list
  does not price, and every entry of a provider that publishes no list, keeps the prices a
  person typed.
- **Every rate the provider lists for a model is kept on its entry.** Beside the four prices
  the discovery writes `listed_rates`: each row the provider's price list states for the
  model, under the name the provider gives the row and per million tokens, in the entry's
  currency.

  ```json
  "listed_rates" : {
    "USE1-MP:USE1_InputTokenCount-Units" : 3.3,
    "USE1-MP:USE1_InputTokenCount_Batch-Units" : 1.65,
    "USE1-MP:USE1_InputTokenCount_Global-Units" : 3.0
  }
  ```

  No name is interpreted and no row is left out for what its name says: the batch, flex and
  priority tiers, the long-context rates, the cache write held an hour, the rates of the
  other deployment types, and whatever the provider adds next. Nucleo prices no call from
  them; they are the record of what the provider charges, and what a
  [spec class of your own](../PACKAGE.md) prices a call from. Each run replaces an entry's
  rows whole, and a run that read none leaves the earlier ones. Bedrock entries take theirs
  from the AWS price list and Azure AI Foundry entries from Microsoft's retail price list,
  both public; an entry of a provider that publishes no list has none.
- **An Azure AI Foundry entry's prices stay as you typed them.** Microsoft prices a model
  per deployment type, a Data Zone deployment above a Global one, and the
  listing a resource key reads does not say which type a deployment is. So the discovery
  writes the rows of every type into `listed_rates` and no price. What it can tell it
  reports: under `PRICES THAT ARE NONE OF THE LISTED ONES` the report names each entry whose
  price is the standard price of no deployment type, with the price and the ones the list
  states, and leaves the entry as it is.
- **Nothing is deleted, and kept is all it is.** An entry the account no longer lists is
  closed as `UNLISTED`; an entry of a provider whose endpoint is not served where this
  deployment points, or whose own ping fails for a reason no retry changes, is closed as
  `UNREACHABLE`. No new work goes to a closed entry while past calls keep their price; when
  the account lists the model again, or the provider answers again, the entry reopens.
- **New models follow their family.** A new model of a family the file already has is added
  with the shape of its nearest older relative, and a note saying so; where the provider's
  price list prices it, the prices are the list's and the note names the rest to verify. A
  new version of a family you disabled arrives disabled. A newer version of a pinned model is
  added and announced at the top of the report, and the pin stays where you put it.
- **Every entry's thinking mode is learned from the provider.** A new model arrives with
  the thinking mode of its older relative, and providers change how their models think from
  one generation to the next, so the discovery checks it, for every chat entry and whatever
  mode the entry carries, `NONE` included. Where the provider's listing states how a model
  thinks, as Anthropic's does, that is the entry's mode. Otherwise the entry is asked to
  think, with a question of a few steps, in each way its provider's client can ask: with an
  adaptive or a budgeted thinking request for Claude, with a reasoning effort for the
  OpenAI dialect and Bedrock's Converse API. A way counts when the answer carries the
  model's reasoning, a thinking block or the provider's count of reasoning tokens. An
  accepted request proves nothing by itself, because a model or a host that ignores the
  parameter answers all the same; the one exception is Anthropic's API, which refuses a
  thinking type the model does not take, so there the acceptance is the proof. The first
  way the model reasons in is the entry's mode, and an entry no way makes reason is `NONE`.
  The report's `THINKING MODES` block lists each corrected entry with the mode before and
  after, each entry whose written mode was accepted without any reasoning in the answer as
  `NOT CONFIRMED`, and each entry whose thinking call failed for an unrelated reason as
  `NOT CHECKED`. An open entry already in your file is checked the same way on every run,
  without a ping and with nothing else of it touched.
- **What a run pays for**: the listings it has not seen, the classification of those that
  need one (below), one call per new entry, and the thinking check of every open chat
  entry, new or already in the file: one call where the model reasons as written, a few
  where it does not. The checks of the entries already in the file run side by side. A
  provider whose thinking calls run out of time twice is not asked for its remaining
  entries, which the report lists as `NOT CHECKED`.
- **What the thinking check never does**: it reads a failure as a refusal of thinking only
  when the provider answered the request itself and its text names thinking or reasoning,
  the entry's own id set aside; a failure to authenticate or to reach the provider is never
  one. A model that refuses the reasoning effort one depth sends is asked at the next
  before its mode is given up. A model that reasons at another depth alone keeps its mode
  and is reported as `BROKEN AT STANDARD`: the runtime sends the effort of each call's own
  depth, so calls at the depth it refused will fail, and the entry is yours to disable or
  to keep for the depths it takes. An entry that
  carries its own `thinking_budgets` loses them when its mode changes, since they were
  written for the mode it had, and the report says so. A model that does not take text and
  answer in text is not asked.

A listed model with no older relative in the catalog is written, where it can be, by the
strongest model the run can call, under strict checks: the proposal must name a model the
account listed, in the shape the catalog requires, and is kept only when a live call to it
succeeds. The entry's note names the model that wrote it, so a person knows which facts to
check, and the entry carries `unverified` until a person confirms it, as an entry inherited
from an older relative does. A model it cannot place stays in the report for a person to
write.

The grade such an entry gets follows one rule, in `GradeCriterion` and in the classifier's
instructions: the vendor's own tier, then the model's generation, never its price. A tier word
in the listed name decides by itself, and the run applies it before anything else: `nano`,
`micro`, `lite` and `tiny` are MICRO; `mini`, `small`, `haiku` and `flash` are SMALL;
`medium` and `sonnet` are MEDIUM; `large` is LARGE. For the rest the classifier judges under
the same rule: an open-weight model by its parameters, under 10B MICRO, to 35B SMALL, to 150B
MEDIUM, above LARGE, counting the active parameters per token for a mixture of experts and
never the total; a vendor's current flagship XL, a flagship one generation behind LARGE, and
MEGA above the flagship. An expensive old model is an old model.

Everything the discovery could not settle is in its report, never guessed: a provider whose
credential is missing, a model the account lists that no client in the runtime can call, a
model refused because it cannot run at zero data retention.

## How it works inside

The provider walk, the ping, the verdicts, the merge rules and the classifier's gates are
in [Inside the discovery](HARNESS_MODELS_DISCOVERY_INTERNALS.md), for those working on the
runtime itself.
