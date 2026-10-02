# Inside credentials

This page is for people working on the runtime itself. Where a deployment's credentials come
from, and how to provide them, is in [Credentials](PACKAGE.md).

## What is here

| Type | Role |
|---|---|
| `Secrets` | The seam. `find(id)` answers null when the deployment holds nothing under the id; `require(id)` throws instead. `Secrets.configured()` is the deployment's store, built once from `SecretsSettings.secretsClass`. |
| `SecretsSettings` | The package's knob: `secretsClass` names the store implementation. |
| `Credential` | What comes back: `user`, `secret`, `host`. A part the store does not hold is null. Never prints its secret. |
| `SecretUnavailableException` | Unchecked. Nothing under the id, or a store that cannot answer. Carries the id, and the store's own failure as the cause when there is one. |
| `EnvironmentSecrets` | The shipped default: environment variables, read by each credential's shape, the one rule for an id nobody declared. |
| `CredentialShape` | What a credential is made of, declared once by the code that owns its id: which of the three parts it has, the environment variable each part reads from, and what each means to a person. `describeVariables()` and `describeProperties(prefix)` are the words every store's refusal uses. |
| `CredentialShapes` | The shapes on record, by id: a `ClientProvider` declares the shapes of the credentials it reads (`credentialShapes()`) and the provider registry puts them on record as it loads; an id nobody declared answers the generic rule; a second, different shape for one id is refused. |

## The shape of a credential

A credential is read by its shape (`CredentialShape`): the code that owns an id declares which
of the three parts the credential has and the environment variable each part reads from, so
the variables the runtime reads are exactly the ones a person is asked for, and no part a
credential does not have is asked for or read. A `ClientProvider` declares the shapes of the
credentials it reads and the provider registry puts them on record as it loads
(`CredentialShapesTest`, `ClientProvidersTest`). By default a provider declares one
credential, its `credentialId()`, as an API key alone under the one rule; a provider whose
credential has a host, a user, or that reads several credentials declares them. Declaring
one id twice with the same shape is accepted (two providers riding one credential each
declare it); with a different shape it is refused.
