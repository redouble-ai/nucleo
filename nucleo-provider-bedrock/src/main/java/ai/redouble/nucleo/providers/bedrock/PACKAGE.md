# ai.redouble.nucleo.providers.bedrock

This provider calls models served by Amazon Bedrock in your own AWS account: Amazon's own
models, the other vendors Bedrock hosts, and Claude. Prompts go to your account's Bedrock
endpoint in the region you choose, under your account's model access, quotas and data
retention settings. The library is `nucleo-provider-bedrock`, built on the AWS SDK alone.

## The credentials

Bedrock needs an identity and a region, and neither implies the other: an AWS key works in
every region, while model access, quotas and the model listing are granted per region.

- **The identity**: `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`, or anything else AWS's
  own resolution finds, a profile or the role the process runs under on ECS, EC2 or EKS.
- **The region**: `AWS_REGION`, or AWS's own region resolution. The runtime never picks one.

A deployment that keeps secrets in its own store holds them under the credential ids
`aws-access-key-id` (both halves of the pair) and `aws-region`, and those are used before
AWS's resolution. The provider reports the region it resolved among its connection facts,
so a status page shows it before the first call: a wrong region fails every call with an
error that names neither the region nor the model. [Set up and run the demo](../../../../../../../../../AGENTS.md)
says how a credential reaches the runtime.

## What it serves

Two providers share that credential:

- `bedrock-converse` calls any chat model Bedrock serves through its Converse API, Claude
  included. Pictures and documents (PDF, Word, Excel, CSV, HTML, text, Markdown) go to the
  model as themselves.
- `bedrock-cohere-embeddings` calls Cohere's embeddings models on Bedrock.

Claude on Bedrock with extended thinking, prompt caching, and through Bedrock's Mantle
endpoint, is the add-on library `nucleo-provider-bedrock-anthropic`
([Bedrock through the Anthropic SDK](../../../../../../../../../nucleo-provider-bedrock-anthropic/src/main/java/ai/redouble/nucleo/providers/bedrock/anthropic/PACKAGE.md)).
A build that cannot carry the Anthropic SDK, such as a native image, leaves the add-on out
and reaches Claude through Converse.

The library ships a small catalog of its own for these two providers, with AWS's published
default quotas, used when the deployment has no `models.json` of its own. The discovery
([Discovering an account's models](../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/models/discovery/PACKAGE.md))
lists the models the account can call in the region, and reads each model's tokens and
requests per minute from AWS Service Quotas, since Bedrock sends no rate limits on its
responses. A key that may not read Service Quotas still works: each model keeps the limit
the shipped catalog gives it, and the discovery says so.

## Keeping failover on Bedrock

When an endpoint is overloaded, a picker may move work to the same model on another
endpoint ([A picker of your own](../../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/models/PACKAGE.md)).
This library ships two picker bases for deployments that need a promise about where prompts
go: `BedrockOnlyModelPicker`, whose substitutes are always Bedrock endpoints and never a
provider's direct API, and `ZdrOnlyModelPicker`, which also keeps every model it serves, its
own choices included, to ones that run at zero data retention.

## How it works inside

The encoders, the transport's timeouts and the detail of the account listings are in
[Inside the Bedrock provider](PROVIDERS_BEDROCK_INTERNALS.md), for those working on the
runtime itself.
