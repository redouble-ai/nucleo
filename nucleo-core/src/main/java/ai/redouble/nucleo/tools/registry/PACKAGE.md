# Package: ai.redouble.nucleo.tools.registry

A thinker's palette is the set of tools its model is shown and may call ([Tools, thinkers and
doers](../PACKAGE.md)). Which tools those are is a decision about what an agent may do, so it
is made in code, before the model sees anything. This page explains how a palette is built:
the tools a thinker always has, the tools its model may ask for while it runs and the rules
that decide whether it gets them, the skills it may pull in the same way, and what a
read-only binding does to all of it.

## The tools a thinker always has

A thinker's palette starts with what it declares:

- **`declareDefaultTools()`** returns tool classes, as in `OrderAgent`.
- **`declareDefaultProviders()`** returns tools that are not classes of your own, such as the
  tools of an MCP server ([Calling MCP servers](../../mcp/PACKAGE.md)). A tool that is not a
  class reaches a palette as a `ToolProvider`, the description Nucleo keeps of every tool: its
  name, its description, the schema of its input, whether it is read-only, and how to build
  it for a call. `addTool(provider)` adds one to a running thinker.

To these every thinker adds the tools registered for all thinkers
(`ToolHub.getInstance().registerSystemWideTool`), the two artifact tools
`get_artifact_field` and `search_artifact_content`, and `sub_thinker` while it works at
`Depth.STANDARD` or deeper.

## Tools the model may ask for: `request_tools`

A palette that holds every tool a thinker might ever need costs tokens on every turn and
distracts the model. The alternative is a catalog: tools the thinker is allowed to use but
that are not loaded until its model asks. A thinker declares its catalog by overriding
`declareCompatibleTools()` and returning a `ToolSelector`:

```java
@Override
protected ToolSelector declareCompatibleTools() {
    ToolSelector sel = new ToolSelector("ai.redouble.nucleo.ext.lit");
    sel.addToolPackage("ai.redouble.nucleo.ext.patent.epo");
    sel.addClass(SomeSpecificTool.class);
    return sel;
}
```

A selector takes tool classes, whole packages (every class in the package itself, not its
subpackages, that carries `@ToolName`), and providers. While the catalog is not empty, the
thinker's palette holds `request_tools`. Its input lists the catalog's tool names, each with
the tool's own description, as the only values the model may send, so the model can ask for
what the catalog holds and nothing else. A tool it asks for is checked by the admission rules
(below), added to the palette, and callable from the next turn on; a refused name comes back
with the reason. The catalog is shown to the model only through that input, never as text in
the prompt.

`ToolHub` resolves a thinker class's catalog once and keeps it. Code that connects a tool
source while the application runs, an MCP server for example, widens a thinker class's catalog
with `ToolHub.getInstance().registerCompatible(thinkerClass, providers...)` or
`registerTool(thinkerClass, toolClass)`, without rebuilding any thinker. A thinker narrows its
catalog per turn by overriding `reconcileCatalog`: a tool removed there is neither listed nor
admitted.

## The rules that admit a requested tool

A tool in the catalog is not yet granted. When the model asks for it, `ToolHub` runs the
admission guardrails that apply, and the tool joins the palette only when every one of them
passes. Rules are registered on the hub for a thinker class (or `null` for every thinker) and
match the requested tool by its class, by its name or a glob of names such as `"github.*"`, or
by a predicate over its provider:

```java
ToolHub.getInstance().registerAdmission(ResearchCoordinator.class, "github.*", CodeAccessGuardrail::new);
```

`ResearchCoordinator` and `CodeAccessGuardrail` stand for your own thinker and admission
guardrail. The third argument builds a fresh guardrail per check from the requesting thinker.
The admission guardrails a tool class declares itself run as well. A guardrail's refusal reaches the model
as the reason the tool was not granted, and a guardrail that crashes reaches it as an internal
error, never as a verdict. Every grant and refusal goes through this one door, so the record
of what a model asked for and what it got is complete. [Scope and the trust
boundary](../guardrails/PACKAGE.md) explains the kinds of guardrail.

## Skills the model may pull in: `request_skill`

A skill is a packaged set of instructions ([Skills and skilljars](../../prompt/skill/PACKAGE.md)).
A thinker names skills it always runs with in `declareDefaultSkills()`, and skills its model
may pull in while it runs in `declareCompatibleSkills()`, which returns a `SkillSelector`:

```java
@Override
protected SkillSelector declareCompatibleSkills() {
    SkillSelector sel = new SkillSelector("delegation");
    sel.addBundle("ai.redouble.skills.");
    return sel;
}
```

A selector names skills exactly, by a prefix of their bundle id (so one line offers a whole
skilljar), or all skills the registry holds (`addAll()`). It is read when `ToolHub` first
resolves the thinker class's catalog, and the catalog is kept from then on;
`ToolHub.getInstance().registerCompatibleSkills` widens it later. A name the registry does not
hold is logged and skipped, so a missing bundle never takes the thinker down.

While the catalog is not empty, the palette holds `request_skill`, built like `request_tools`:
the catalog's names are the only values its input accepts. A skill the model asks for passes
the skill admission rules (`ToolHub.registerSkillAdmission`, by skill name or glob, or by a
predicate over the skill) and is then attached to the conversation: its instructions go with
every later call. `reconcileSkillCatalog` narrows the catalog per turn.

## Read-only palettes

A thinker bound to tools that change nothing ([Tools, thinkers and doers](../PACKAGE.md)) has
every tool not marked read-only removed from what its model is shown, each removal logged, and
any call to such a tool refused. The check at the call is the one that holds, since a palette
can grow after the model was shown it: through `request_tools`, a system-wide tool, or a direct
`addTool`. `request_tools` itself is removed from a read-only palette, because it can admit
any tool; `request_skill` stays, because admitting a skill changes the conversation's
instructions and nothing outside it.

## How it works inside

The `ToolProvider` contract, the registry's rules for names and duplicates, the two
enforcement points of the read-only binding, the meta-tools behind `request_tools` and
`request_skill`, and the tests that pin them are in [Inside palettes and the tool
registry](TOOLS_REGISTRY_INTERNALS.md), for those working on the runtime itself.
