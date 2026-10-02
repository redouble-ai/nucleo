---
name: models-catalog
description: Build or refresh this deployment's models.json - the catalog of LLM endpoints the runtime may use, with this account's real rate limits and the pins that say which entry serves which grade. Use when the user asks to set up models, add a model, fix "no model picker" or "not configured" errors, record raised rate limits, or after a provider ships a new model. Runs the deterministic discovery first and reasons only about what it cannot answer.
---

# Build the deployment's models.json

The runtime runs on shipped fragments (public facts, entry-tier limits) until the
deployment has a `models.json` of its own at the classpath root. This skill produces that
file with one command and finishes by hand only what no API can answer.

## 1. Run the discovery, report only

From the deployment's project, on a classpath that carries the provider artifacts it uses
and the credentials in the environment (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, and for
Bedrock AWS's own `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_REGION`, or a
profile, or the role the process runs under):

```
mvn -q exec:java -Dexec.mainClass=ai.redouble.nucleo.harness.models.discovery.CatalogDiscovery -Dexec.args="--report-only"
```

In this repository the demo jar runs it with the demo's own credential binding (Boot
properties from the environment), which a bare `exec:java` does not have:

```
java -jar nucleo-demo/target/nucleo-demo.jar discover --report-only
```

Read the whole report before saying anything. It has three sections: per provider, per
entry, and the models the account lists that no entry names.

## 2. Explain the report in the user's terms

- `NOT CONFIGURED - provide ...`: the provider's credential is missing. Name the exact
  variable or store entry from the line. Its seed entries are left out of the file until
  it is provided. Do not suggest a workaround.
- `LISTING FAILED`: the credential exists but the listing call was refused or failed. On
  AWS this is IAM (`bedrock:ListFoundationModels`, `bedrock:ListInferenceProfiles`,
  `servicequotas:ListServiceQuotas`, `bedrock-mantle:ListModels`), on Anthropic and OpenAI
  a rejected or scoped key. Quote the provider's message verbatim.
- `UNREACHABLE AUTH`: the listing worked but the call was refused - on Bedrock usually
  model access not enabled for the account, on Mantle a project or retention refusal.
- `UNREACHABLE AVAILABILITY` / `THROTTLE`: the model exists but did not answer now. A
  re-run later is the fix; never close its status for this.
- `NOT LISTED`: the account cannot see the model on that provider. For Bedrock, model
  access is granted per model in the console; for Anthropic and OpenAI the key's tier or
  organization does not include it.
- `(no quota matched)` in a Bedrock note: AWS spells that model differently in its quota
  names, so the entry keeps its seed limit. Offer to look the number up in the Service
  Quotas console and set `tpm` / `rpm` by hand; say plainly that it is unverified.
- Limits shown as `observed` came from the provider's response headers and are this
  account's; `quota` came from Service Quotas. Neither is ever invented.

## 3. Handle the unknown models

The discovery classifies most of these itself: when the run can call a model, the strongest
callable one writes entries for the listings nothing could inherit a shape from (the report's
"Classified by <id>" line names them; each entry carries a note saying what to verify). Your
step 3 is what remains: the models the classifier was not confident about, runs with no
callable model, and the verification of classified entries the user asks for.

For each model still under "listed by the account, no entry and no ancestor", propose one
complete entry, and mark every value as proposed until the user confirms:

```
{ "id": "<identity>-<endpoint>", "identity": "<model family and version>", "grade": "<MICRO|SMALL|MEDIUM|LARGE|XL|MEGA>",
  "provider_key": "<the provider that listed it>", "wire_model_id": "<exactly as listed>",
  "max_context_tokens": ..., "max_output_tokens": ..., "supports_vision": ..., "thinking_mode": "<NONE|EXTENDED|ADAPTIVE|REASONING_EFFORT>",
  "tpm": <the fragment's value for the closest sibling on the same provider>, "rpm": ...,
  "input_price_per_million": ..., "output_price_per_million": ... }
```

A price is a number in a currency: the entry's `currency` (an ISO 4217 code) or its
provider defaults' applies, and the shipped defaults say `USD`. Price a model in the
currency its list page bills in and set `currency` on the entry when that differs from
the provider's default; never convert.

Public facts (context, output ceiling, vision, thinking mode, list price) come from your
knowledge of the model and must be checkable; say where each comes from. The grade is a
judgment on the ladder relative to the entries already in the file; propose one and give
the comparison. `tpm` and `rpm` are the account's: seed them from the sibling entry and
let the next run observe the real numbers. A model the provider marks retired goes in with
`"status": "DEPRECATED"` only if the user wants history to price against it; a model the
user does not want served is `"status": "DISABLED"`, with a `note` saying why. Write the
confirmed entries into the file and re-run step 1 so they are pinged.

## 4. Pins

Ask what the deployment wants served per grade in one question, offering the reachable
entries by grade from the report. Typical answers: one provider only; zero data retention
(Bedrock routes without `requires_lax`); cheapest per grade; one frontier model plus one
cheap one. Write the answer as the `pins` object. Each grade maps to an array of catalog ids
in the deployment's order: the first is the grade's default, and the rest serve when it
cannot be called or does not accept what a request sends, such as images. `embeddings` names
the embeddings entry the corpus will be tied to. The strongest grade served is derived and
never pinned:

```
"pins": { "SMALL": ["...", "..."], "MEDIUM": ["..."], "XL": ["..."], "embeddings": "..." }
```

Say once that the embeddings pin is a corpus decision: every stored vector is comparable
only to vectors from that model, so it changes only with a deliberate migration.

## 5. Write and verify

```
mvn -q exec:java -Dexec.mainClass=ai.redouble.nucleo.harness.models.discovery.CatalogDiscovery -Dexec.args="--out src/main/resources/models.json"
```

or, in this repository, `java -jar target/nucleo-demo.jar discover` from
`nucleo-demo`, which writes `src/main/resources/models.json`: the build carries it onto the
classpath, where the runtime finds it like logging configuration, however the demo is launched.

The tool validates the file by loading it before writing. Add the pins and any hand
entries with an edit, then run step 1 again on the written file (the runtime reads the file
`-Dnucleo.models` names, else `models.json` on the classpath, nearest first): the report's `Previous file`
line confirms the file was read, and its `Pins:` block lists every pin with its entry's
verdict, each of which must read `REACHABLE`. Commit the file; it is the deployment's, not the
runtime's.

## Rules

- Never invent a rate limit, a quota, or access. Observed, quota, seed, or stated by the
  user, in that order, and say which.
- Never remove an entry from an existing file. Deprecate it, because history prices
  against it.
- Never change a value the user set by hand unless a run observed a different one, and
  then show the difference before writing.
- The report is the evidence. Do not summarize it away; quote the line that supports
  each claim.
