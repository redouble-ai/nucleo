# Package: ai.redouble.nucleo.secrets

Calling a model provider, a patent office or PubMed takes a key. Nucleo never goes looking
for keys: each piece of code that needs one asks the deployment's credential store for it by
a fixed id, such as `anthropic-api-key`, and the store answers. Which store answers is the
deployment's choice: environment variables by default, the application's properties in a
Spring Boot or Quarkus application, or a vault through a class you write. The code that
asks stays the same whatever answers.

Each provider and tool declares the id of its own credential as a constant in its own
package (`AnthropicSDKClient.SECRET_ID` is `anthropic-api-key`). Nothing outside that
package needs the id, so the runtime keeps no registry of ids; the table below lists the
ones that ship. What an id maps to in a store is the deployment's business.

## What a credential is

A `Credential` has up to three parts: a `user`, a `secret` and a `host`. An API key is a
secret alone. The EPO key is a pair, a consumer key as the user and a consumer secret. An
Azure Foundry key is a secret and the host it belongs to. A part the store does not hold is
null, and a credential never prints its secret: its `toString()` masks it, so a log line
can carry one.

## Running on environment variables

The store a deployment gets without configuring anything is `EnvironmentSecrets`, so a
checkout with the variables exported runs with no code. Each credential is read from the
variables its owner declared for it: the Anthropic key from `ANTHROPIC_API_KEY`, the Bedrock
pair from AWS's own `AWS_ACCESS_KEY_ID` (the user part) and `AWS_SECRET_ACCESS_KEY` (the
secret), the Bedrock region from `AWS_REGION` alone, an Azure Foundry key from
`AZURE_FOUNDRY_API_KEY` with `AZURE_FOUNDRY_API_KEY_HOST` for the endpoint. The variables the
runtime reads are exactly the ones a person is asked for, and a part a credential does not
have is neither asked for nor read.

An id nobody declared, such as a tool's own key, follows one rule: the id in upper case,
every character other than a letter or digit turned into an underscore, names the secret,
and the suffixes `_USER` and `_HOST` name the other two parts. `epo-api-key` reads
`EPO_API_KEY`, with `EPO_API_KEY_USER` for the consumer key.

A credential exists when any of its variables is set. An empty value counts as unset.

## The credentials Nucleo asks for

| Id | Environment variables | Unlocks | Declared by |
|---|---|---|---|
| `anthropic-api-key` | `ANTHROPIC_API_KEY` | the direct Anthropic API | `AnthropicSDKClient.SECRET_ID` |
| `openai-api-key` | `OPENAI_API_KEY`, and `OPENAI_API_KEY_HOST` for Azure | OpenAI and Azure OpenAI | `OpenAIProvider.SECRET_ID` |
| `openai-compatible-api-key` | `OPENAI_COMPATIBLE_API_KEY` (the bearer token), `OPENAI_COMPATIBLE_API_KEY_HOST` (the API root the paths are appended to, `https://router.huggingface.co/v1` for Hugging Face Inference Providers, `http://localhost:11434/v1` for Ollama) | any endpoint that speaks the OpenAI dialect without being OpenAI | `OpenAICompatibleClient.SECRET_ID` |
| `azure-foundry-api-key` | `AZURE_FOUNDRY_API_KEY` (the resource key), `AZURE_FOUNDRY_API_KEY_HOST` (the resource hostname) | Azure AI Foundry's OpenAI-compatible surface | `AzureFoundryOpenAIClient.SECRET_ID` |
| `aws-access-key-id` | AWS's own `AWS_ACCESS_KEY_ID` (user = the key id) and `AWS_SECRET_ACCESS_KEY` (secret); with neither, or only one of them, the AWS default chain resolves the identity (a profile, or the process's role). In a store of the deployment's own, the record holds the key id as the user and the secret access key as the secret | Bedrock | `BedrockClients.SECRET_ID` |
| `aws-region` | `AWS_REGION`, which is also what the AWS default region chain reads (then a profile, then instance metadata) | Bedrock: the region every call goes to. A key is account-wide and implies no region, and Bedrock never defaults one | `BedrockClients.REGION_ID` |
| `systemone-api-key` | `SYSTEMONE_API_KEY` (the bearer token the endpoint checks), `SYSTEMONE_API_KEY_HOST` (the API root) | a hosted decision model ([Connecting a decision model](../../../../../../../../nucleo-provider-systemone/src/main/java/ai/redouble/nucleo/providers/systemone/PACKAGE.md)) | `SystemOneClient.SECRET_ID` |
| `systemone-local` | `SYSTEMONE_LOCAL_HOST` (the address of a decision model server on this machine, which checks no key) | a local decision model | `SystemOneClient.LOCAL_ID` |
| `epo-api-key` | `EPO_API_KEY_USER` (consumer key), `EPO_API_KEY` (consumer secret) | EPO OPS | `EPOClient.SECRET_ID` |
| `myodp-api-key` | `MYODP_API_KEY` | USPTO Open Data Portal | `UsptoOdpClient.SECRET_ID` |
| `ncbi-api-key` | `NCBI_API_KEY` | NCBI's authenticated rate limit for PubMed and PMC | `PubMedRateLimiter.NCBI_SECRET_ID` |

## When a credential is missing

Nobody has to read source to learn what to set. Every "not configured" message goes through
`Secrets.describe(id)`, which the store answers in the credential's own terms. The
environment store names the declared variables with what each means (`the environment
variables AWS_ACCESS_KEY_ID (the access key id) and AWS_SECRET_ACCESS_KEY (the secret access
key)`), or, for an undeclared id, the variable and its two conditional suffixes (`the
environment variable NCBI_API_KEY (and NCBI_API_KEY_USER / NCBI_API_KEY_HOST when the
credential has a user or host part)`). The Spring Boot and Quarkus stores name both the
properties to bind and the variables. A store with no words of its own is described as `the
credential '<id>' in <its simple class name>`. So the first run without a key logs, or
fails with, the exact thing to provide.

Whether a missing credential is a failure depends on the code that asks, and the store has
two questions for the two cases. `require(id)` throws `SecretUnavailableException` when the
deployment holds nothing under the id; `find(id)` answers null.

- **A client built around a key calls `require`.** `AnthropicSDKClient` and the OpenAI
  clients fail when they are constructed without their key: a deployment fault, reported
  once, where it happens. A provider's model listing calls `require` the same way, since a
  listing without a key is the same fault.
- **A tool that can work without its key calls `find`.** The PubMed tools, whose key only
  lifts a rate limit, carry on without it; the EPO and USPTO ODP tools say "not configured"
  when they are used.
- **Whether a provider is configured is a question, never a failure.** By default
  `ClientProvider.configured()` asks `find`, so a missing key reads as "not configured" and
  `DefaultModelPicker` passes over that provider's models. The Bedrock provider answers it by
  asking whether a region and an identity resolve, from the store or from the AWS chains.

## Amazon Bedrock and the AWS chain

Bedrock is the one provider whose credentials are not the runtime's to name: AWS has its
own convention, every SDK, CLI and container honors it, and a person with AWS access already
has the three values in that shape. So `BedrockClients` calls `find` for both of its ids,
uses the store's record when it holds the whole pair, and otherwise hands the AWS SDK its
default chains, which resolve the standard variables, a profile, or the role the process runs
under. A deployment on a role provisions no key anywhere. The environment store reads the
pair as AWS's own `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`; half a pair is no record,
so the chain reads it as AWS does.

## Credentials from your own store

The ids above are what a consumer needs, and they are never what a store calls its records.
A store's names belong to the deployment: a vault hands them out, one endpoint may have
several credentials, and no library can know them. Binding an id to a record is each host's
own configuration mechanism, the same way a datasource is bound:

- **A Spring Boot application** reads `nucleo.credentials.<id>` from Spring's
  `Environment`, so a profile file, an environment variable, a Vault path or a Kubernetes
  secret all bind it. Any part left unbound is read from the environment variable of the rule
  above, so the variables alone run a Boot application too
  ([Spring Boot](../../../../../../../../nucleo-spring-boot-starter/src/main/java/ai/redouble/nucleo/spring/PACKAGE.md)).
- **A Quarkus application** reads the same properties through MicroProfile Config, with the
  same fallback to the variables
  ([Quarkus](../../../../../../../../nucleo-quarkus/src/main/java/ai/redouble/nucleo/quarkus/PACKAGE.md)).
- **A bare JVM** reads the variables themselves.
- **A host with a store of its own** writes one class implementing `Secrets`:

```java
public class VaultSecrets implements Secrets {
    public Credential find(String id) { ... }   // null when the vault has none
}
```

and assigns it in its configurator
([Settings and the configurator](../PACKAGE.md)):

```java
Settings.get(SecretsSettings.class).secretsClass = VaultSecrets.class;
```

`find` answers null when the store holds nothing under the id, and throws
`SecretUnavailableException` when the store cannot answer at all (unreachable, unreadable,
misconfigured); the exception carries the id, and the store's own failure as its cause.
Overriding `describe(id)` gives the store's own words to every "not configured" message,
naming the record a person should create. The store is built once, by reflection through a
no-argument constructor, the first time anything asks `Secrets.configured()`.

Two credentials for one endpoint are two ids, bound the same way, with the catalog entry
naming which one it rides.

## A key for your own tool

A tool of your own that needs a key declares its id as a constant in its own package and asks
for it with `Secrets.configured().find(id)` or `require(id)`, whichever fits the cases above.
Its variables follow the one rule, so `acme-api-key` reads `ACME_API_KEY`, and every store,
including the Boot and Quarkus ones, binds it with no further code.

## How it works inside

The types of the package and how credential shapes are put on record are in
[Inside credentials](SECRETS_INTERNALS.md), for those working on the runtime itself.
