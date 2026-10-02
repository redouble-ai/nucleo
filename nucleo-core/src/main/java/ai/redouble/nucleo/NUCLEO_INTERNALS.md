# Inside settings

This page is for people working on the runtime itself. How to tune a deployment is in
[Settings and the configurator](PACKAGE.md).

## What is here

| Type | Role |
|---|---|
| `Settings` | The base of every runtime tunable. `Settings.get(HttpSettings.class)` answers the one instance per type, after the deployment's configurator has run. |
| `NucleoConfigurator` | The whole configuration surface a deployment author touches: one class, one `configure()` method assigning the settings fields that differ from the shipped defaults, and `printAll()` for the whole-deployment view. |

## Tests

`SettingsContractTest` pins the contract: one instance per type; the named class wins over
registrations; the single registration runs; none means shipped defaults; two refuse naming
both and the property; `printAll` shows every registered knob with its live value, including
what the discovered configurator assigned. `SettingsPoisonedConfiguratorTest` pins the
poison rule in a forked JVM, because a failed configurator poisons its process for good.
The cross-jar half of `printAll` is pinned from the starter artifact
(`NucleoAutoConfigurationTest.printAllListsSettingsContributedByOtherJars` sees
nucleo-core's settings). The suite's own configurator, `TestConfigurator` in the test tree,
is the discovery mechanism's standing end-to-end exercise: every model-resolution test
resolves through the picker it assigns.
