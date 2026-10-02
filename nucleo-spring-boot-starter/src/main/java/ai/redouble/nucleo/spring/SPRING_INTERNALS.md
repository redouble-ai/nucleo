# Inside the Spring Boot starter

This page is for people working on the runtime itself. How to run Nucleo in a Spring Boot
application is in [Spring Boot](PACKAGE.md).

## What is here

| Type | Role |
|---|---|
| `SpringSecrets` | The runtime's credential store: `nucleo.credentials.<id>` answers the secret, `.user` and `.host` the other parts. A bean, so Spring's `Environment` reaches it; the runtime builds its instances by reflection and they read the same environment. |
| `SpringEnvironment` | The `NucleoConfigurator` the starter registers under `META-INF/services`: it assigns `SpringSecrets` as the store and leaves every other default alone. |
| `SpringSettingsBinder` | Maps `nucleo.*` application properties onto the registered `Settings` classes: `nucleo.http.pool-size` binds `HttpSettings.poolSize` - the class minus `Settings` and the field, both kebab-cased. Runs before any bean builds; a bound property overwrites what the configurator set. |
| `NucleoRuntime` | A `SmartLifecycle` bean at phase 0, below the web server's: starts the dispatcher, subscribes the event log and the `CostLedger`, registers the ledger as the spend gate, and drains the dispatcher on stop - only a dispatcher it started itself, since the dispatcher is process-wide. Stays down in discover mode. |
| `NucleoSpringApplication` | `run(Application.class, args)` in place of `SpringApplication.run`: `discover` as the first argument runs `CatalogDiscovery` in a context without the web server and exits with its code. |
| `NucleoAutoConfiguration` | Declares the beans - the store, the runtime and the database registration each `@ConditionalOnMissingBean`, so a host that declares its own replaces them, plus the settings binder. |
| `NucleoDatabase` | Calls `DBResourceProviders.registerDefault()` once every bean exists, registering the core `CountingDBResourceProvider` from `nucleo.database.*` when `max-concurrent` is set; unregisters it when the context closes. |
| `SpringTransactionResourceProvider` | Opt-in: the `DBResourceProvider` over a Spring `ResourceTransactionManager` that holds one connection per job from admission to its end, so a job's repositories, `JdbcTemplate` and `@Transactional` services run on the connection it was admitted for. A host registers it itself. |
| `HeldConnection`, `HeldEntityManager` | What the provider holds and binds to a job's thread: a connection under a datasource manager, a Hibernate session under a JPA manager. |

## How the held connection is bound

At admission the provider checks the connection out and binds it to the job's thread the way
Spring's open-in-view support binds a request's: a `ConnectionHolder` under a
`DataSourceTransactionManager`, an `EntityManagerHolder` over a Hibernate session held in
`DELAYED_ACQUISITION_AND_HOLD` under a `JpaTransactionManager`, with the session's connection
bound for the manager's datasource between transactions so plain JDBC beside JPA stays on it.
Every transaction the job runs, and every data access outside one, lands on that connection,
except a transaction that suspends the one under way (`REQUIRES_NEW`): Spring runs it on a
second connection from the pool, outside the admission bound, and hands it back when it ends.
A JPA provider other than Hibernate, and any other kind of resource, is refused when the
provider is built. Spring binds transactions to a thread: `begin`, `commit` and `rollback`
run on the job's thread and refuse any other; a close on another thread - the dispatcher's
timeout enforcement - returns the connection directly, and the job thread's bindings die with
it. `awaitCompletion` returns at once, since the manager's commit is a JDBC commit the server
acknowledged. `SpringTransactionResourceProviderTest` and
`SpringJpaTransactionResourceProviderTest` hold these rules beside the provider contract.

## Discover mode and the runtime

`NucleoRuntime` stays down for discover mode, because the discovery seals its own permitting
envelope to ask what the account can call and a started dispatcher would have sealed the
refusing default first.

## Tests

`SpringSecretsTest` pins the credential store: the three parts from their three properties,
a part no property binds read from its environment variable while a bound part wins, a
declared shape deciding what is read and asked for, and a store asked before the context
exists refusing. `NucleoDatabaseTest` holds the database registration.
`NucleoAutoConfigurationTest` pins the beans the starter declares, the binding of `nucleo.*`
properties, `printAllListsSettingsContributedByOtherJars`, a host's own runtime bean
replacing the starter's, and a context that did not start the dispatcher leaving it running
when it closes.
