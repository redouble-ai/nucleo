# Inside palettes and the tool registry

This page is for people working on the runtime itself: the contracts local to the classes of
`ai.redouble.nucleo.tools.registry`. The guide for building a thinker's palette is [Palettes
and the tool registry](PACKAGE.md); palette assembly, the reconcile hooks and admission are
described for authors in [Tools, thinkers and doers](../PACKAGE.md).

The registry catalogs every tool the process ships (`ToolProvider`, `ToolRegistry`); a
thinker draws its palette from it by declared compatibility (`ToolSelector`,
`SkillSelector`); a palette bound read-only stays read-only (`ReadOnlyPalette`). A model may
ask for more mid-run through two meta-tools (`request_tools`, `request_skill`), and the ask is
judged like everything else: the same declarations and admission rules decide it, so a model
can request what you offered and nothing else, and every grant is on the record.

## ToolProvider: the single abstraction

`ToolProvider` carries everything the thinker pipeline needs to present and invoke a tool:
name, description, JSON schema, weight, display metadata, input parsing, `readOnly()`, and
a factory that builds the concrete `Tool` for a parent. `metadata()` is the open extension
slot beside them - a host-defined provider carries tool information the framework's own
surface does not name (icons, categories, cost hints); the framework reads nothing from it.
Registries store providers, never classes.

- `ClassToolProvider` wraps a `Class<? extends Tool>` and reads its `@ToolName`,
  `@ToolDescription`, `@DisplayName`, `@ToolWeight` annotations. Equality is by wrapped
  class, so registering the same class twice produces the same registry entry. The input
  schema is generated once from the input type and narrowed by the tool's declared
  `@SchemaRefinedBy` refiner; a refiner that fails throws rather than publishing the
  schema it exists to narrow. An output schema is published only for a composite output
  type the walker can describe truthfully; otherwise the provider answers null and the
  output ships as text content. `create(parent)` builds the tool through its
  `(Identifiable parent)` constructor, and a `ModelDependent` tool that declares no grade
  receives its parent's.
- `MCPToolProvider` (in `ai.redouble.nucleo.mcp`) wraps an MCP endpoint's tool descriptor
  and routes calls through the MCP client pool.
- `RequestToolsProvider` / `RequestSkillProvider` are dynamic: their input schema is built
  fresh per turn from the thinker's reconciled catalog, as `oneOf` const entries with each
  member's own description, so the LLM provider's native validation refuses any name off
  the catalog before it can reach admission. The catalog never enters the prompt as free
  text. Two instances are the same registry entry (equality by type), so per-turn
  re-registration replaces rather than duplicates.

`readOnly()` defaults to false because that is the correct answer for a provider that
cannot know: a tool is treated as mutating unless something asserted otherwise.

Parse refusals are correctable: `parseInput` throws `InvalidInputException`, whose message
names the field (from the mapping failure's own reference chain) and the declared type,
never the value the model sent; the decoder's own complaint, which quotes that value,
survives on the cause, where only a stack trace carries it.

## ToolRegistry: names to providers

A registry maps `ToolProvider.name()` to the provider. The duplicate policy: a second
registration under an existing name is an upsert when the new provider has the same
concrete type and name (system-wide plus per-thinker overlap is harmless); a registration
under an existing name with a DIFFERENT provider type throws `IllegalStateException`, so
collisions surface early. Register and unregister are state-idempotent, which is what
makes the per-turn reconcile hooks safe to run every turn. `createTool` on an unknown name
throws a correctable error naming the available tools, so the model can pick another. A
null name - a malformed call can omit one - is just an unknown name to every lookup that
resolves a provider (`getProviderByName`, `createTool`, `getInputTypeByToolName`,
`getDisplayName`, `getActionVerb`), rather than letting the backing map throw on a null key;
`hasTool` and `unregister(String)` go to the map directly and do throw on a null name.

Class-taking conveniences (`register(Class)`, `registerAll`, `unregister(Class)`) wrap the
class in a `ClassToolProvider`; a class without `@ToolName` is skipped with a warning.

## ReadOnlyPalette: the read-only binding, enforced twice

`sweep` removes every mutating provider from a registry and names each removal - it decides
what the model is OFFERED. `requireReadOnly` asserts a tool may RUN, and is the guarantee:
the registry is rebuilt every turn and can be widened after the definitions were built (by
`request_tools`, by system-wide tools, by a direct `addTool`), so only the per-call check
holds. Both are called from `AbstractThinker`'s own call path, outside any overridable
hook. `request_tools` is swept like any mutating tool (it admits arbitrary tools);
`request_skill` survives (admitting a skill changes the conversation's own preamble and
nothing outside it).

## The meta-tools

`RequestToolsTool` and `RequestSkillTool` are resource-free doers a thinker injects itself
into (`ToolRegistryAware`; the skill tool also `ConversationAware`). Each call goes to
`ToolHub` for the reconciled catalog and lazy admission; admitted tools land in the
thinker's registry and their schemas appear on the next call, admitted skills attach to the
conversation and ride its preamble from then on. Rejections come back named with reasons in
`RequestToolsResult` / `RequestSkillResult`. A name already present reads as admitted, not
as an error.

## Selectors

`ToolSelector` accumulates providers from classes, packages (via `ToolHub` package scans)
and direct provider additions; a thinker returns one from `declareCompatibleTools()`.
`SkillSelector` names skills by exact name, by bundle-id prefix, or everything the registry
holds - resolved against `SkillRegistry` when `getSkills()` is called, which `ToolHub` does
once per thinker class before caching the result, so a selector never has to name the skills
a skilljar holds. A declared name the registry does not hold is logged and skipped; the
palette must not go down with a missing bundle.

## Tests

`ReadOnlyPaletteTest` (sweep, admission, defaults, the construction-time latch),
`RequestToolsSchemaTest` / `RequestSkillSchemaTest` (the catalog-as-enum schema and its
snake_case spelling), `RequestSkillPaletteTest` (the per-turn offer and the conversation
landing), `ToolHubSkillAdmissionTest` (the admission door), `SkillSelectorTest`
(resolution-time reads), `ThinkerPaletteContractTest` (registry idempotence, in
`tools.thinking`).
