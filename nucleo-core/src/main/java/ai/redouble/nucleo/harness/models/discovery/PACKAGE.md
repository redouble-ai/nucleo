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
the provider libraries and sees the credentials, prints the report and writes the file; its
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

- **Nothing you wrote is re-checked or rewritten.** An entry already in the file keeps its
  facts exactly as they are and is not called again. Your pins are kept and never moved.
- **Nothing is deleted, and kept is all it is.** An entry the account no longer lists is
  closed as `UNLISTED`; an entry of a provider whose endpoint is not served where this
  deployment points, or whose own ping fails for a reason no retry changes, is closed as
  `UNREACHABLE`. No new work goes to a closed entry while past calls keep their price; when
  the account lists the model again, or the provider answers again, the entry reopens.
- **New models follow their family.** A new model of a family the file already has is added
  with the shape of its nearest older relative, and a note saying so. A new version of a
  family you disabled arrives disabled. A newer version of a pinned model is added and
  announced at the top of the report, and the pin stays where you put it.
- **A run pays only for what is new**: the listings it has not seen, the classification of
  those that need one (below), and one call per new entry.

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
