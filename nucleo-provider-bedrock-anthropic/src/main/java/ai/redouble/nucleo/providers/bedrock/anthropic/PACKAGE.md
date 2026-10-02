# ai.redouble.nucleo.providers.bedrock.anthropic

The Bedrock library already reaches Claude through the Converse API
([Amazon Bedrock](../../../../../../../../../../nucleo-provider-bedrock/src/main/java/ai/redouble/nucleo/providers/bedrock/PACKAGE.md)).
This add-on, `nucleo-provider-bedrock-anthropic`, reaches Claude on Bedrock through
Anthropic's own SDK instead, for what Converse does not carry: extended thinking, prompt
caching, and Bedrock's Mantle endpoint. The Anthropic SDK has no native-image support,
which is why the add-on is a library of its own: a native build leaves it out and keeps
Converse.

## The credentials

The same as the Bedrock library's: an AWS identity and a region (`AWS_ACCESS_KEY_ID`,
`AWS_SECRET_ACCESS_KEY`, `AWS_REGION`, or AWS's own resolution), shared by every Bedrock
provider, so a deployment configured for one is configured for all of them.

## What it serves

Two providers, both for Claude alone:

- `anthropic-bedrock` calls Claude on Bedrock's runtime endpoint, with the inference-profile
  ids the region lists (`us.`, `global.`). Its catalog entries end in `-bedrock`.
- `anthropic-bedrock-mantle` calls Claude through Anthropic's Messages API on Bedrock's
  Mantle endpoint, which names models by bare ids such as `anthropic.claude-opus-5`. Its
  entries end in `-mantle`. Mantle is also where Bedrock says which data retention modes a
  model allows, and some models run only in a mode that shares prompts with the model
  provider; the catalog marks those `requires_lax`.

The library ships a small catalog of its own with the Claude entries of both, used when the
deployment has no `models.json` of its own. Without the add-on those entries are absent,
and Claude on Bedrock is served through Converse. A catalog the discovery wrote with the
add-on on the classpath still loads in a build without it: its `-bedrock` Claude entries are
served through Converse, and its `-mantle` entries are left out.

## Mantle projects

Every Mantle call runs under a Mantle project, and the project decides whether prompts are
retained. The deployment names two in its settings (`ModelSettings`, set in the
configurator described in [Settings and the configurator](../../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/PACKAGE.md)):

- `mantleStrictProject`, a project at zero data retention, for every model that can run
  there. Without it, calls run under the account's own default.
- `mantleLaxProject`, a project whose retention mode is `provider_data_share`, for the models
  marked `requires_lax`. Without it those models are never served, and the picker passes
  over them.

The application's compliance envelope decides whether such a model may serve at all
(`DefaultComplianceEnvelope.setAllowDataShare`), and the Mantle client checks it once more
as the last step before a request leaves the process.

## How it works inside

The classes, how entries link to a provider when a classpath lacks one, and the tests are
in [Inside Claude on Bedrock through the Anthropic SDK](PROVIDERS_BEDROCK_ANTHROPIC_INTERNALS.md),
for those working on the runtime itself.
