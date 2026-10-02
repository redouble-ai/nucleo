# Inside skills

For people working on the runtime itself. What a skill is, how one is packaged and how an
agent gets it are in [PACKAGE.md](PACKAGE.md); this page is the machinery: the types, the
full frontmatter mapping, the rendering and cache placement per provider, the origin
metadata, and the structural validation.

A Skill is a packaging layer on top of `Prompt`: it carries a name, description, body,
named resources, suggested tools, and provenance metadata. The Skill itself is not a
Prompt; it *contains* Prompts.

## Surface

| Type | Role |
|------|------|
| `Skill` | Interface. Name, description, body, resources, suggested tools, metadata. |
| `TextSkill` | Baseline record impl. |
| `SkillMetadata` | Extends `PromptContext`; adds author, license, origin, bundleId, triggerKeyword. |
| `SkillRegistry` | Name-indexed lookup for admission (`register`, `lookup`, `all`, `contains`). Auto-triggers `SkillJarsLoader.scan()` on first access. |
| `SkillJarsLoader` | Discovers classpath-resident skill bundles under `META-INF/skills/` and registers them. Matches the SkillsJars / Anthropic Agent Skills convention: YAML frontmatter + markdown body. |
| `SkillLoadException` | Raised when a skilljar bundle fails to parse or register. |

## The frontmatter mapping

`SKILL.md` frontmatter follows the Agent Skills specification:

| Field | Where | Type | Maps to |
|---|---|---|---|
| `name` | top-level | string | `Skill.name()` (defaults to the leaf directory name when absent) |
| `description` | top-level | string | `Skill.description()` (required, &le; 1024 chars - a longer one fails the bundle) |
| `license` | top-level | string | `SkillMetadata.license` |
| `allowed-tools` | top-level | list of strings | `Skill.suggestedTools()` |
| `compatibility` | top-level | string | parsed, currently ignored |
| `metadata` | top-level | nested map | see below |
| `metadata.author` | nested | string | `SkillMetadata.author` |
| `metadata.license` | nested | string | `SkillMetadata.license` (overrides top-level) |
| `metadata.origin` | nested | string | `SkillMetadata.origin` (defaults to `skillsjars`) |
| `metadata.bundle_id` | nested | string | `SkillMetadata.bundleId` |
| `metadata.trigger_keyword` | nested | string | `SkillMetadata.triggerKeyword` |

Unknown fields are ignored. Body text (everything after the closing `---`) becomes
`Skill.body()`. Every file under the skill directory other than `SKILL.md` registers as a
`Prompt` on `Skill.resources()`, keyed by its path relative to the skill directory. Nested
`SKILL.md` entries are skipped as they belong to separate skill bundles.

## Rendering and cache placement

Once admitted, a Skill rides on `ConversationContext.loadedSkills` and is emitted by each
per-provider LLM client at a provider-appropriate preamble position with
provider-appropriate cache semantics (Anthropic: one system block per skill with a
single ephemeral cache breakpoint on the last system block; OpenAI: prepended
`role:system` message; Bedrock: additional `SystemContentBlock`). Every model-facing text
form is produced in one place by the static `Skill.renderForSystemPrompt(skill)` (system
prefix: header + description + body) and `Skill.renderBody(skill)` (in-message
`SkillBlock`: header + body); no client composes the `[Skill: <name>]` literal itself.

## The model's admission path

`declareCompatibleSkills()` returns a `SkillSelector` (names, a bundle id prefix, or
everything the registry holds). `ToolHub` resolves it once per thinker class, and every
turn the thinker's `reconcileToolRegistry` offers the `request_skill` tool with the
reconciled catalog as an enumeration in its input schema, so the model can only name a
skill on the catalog. A call runs the skill admission rules registered on `ToolHub`
(`registerSkillAdmission`: a thinker class, a skill name or glob or predicate, and an
admission guardrail factory), and each admitted skill is attached to the conversation of
the current turn, which the thinker handed the tool through `ConversationAware`. Rejected
names come back to the model with the reason. It is the same hub, the same admission
machinery and the same schema discipline as `request_tools`, over skills instead of tool
providers.

## Origin

Every Skill carries a provenance string in its metadata:

- **`builtin`** - shipped in the framework's own skills artifact.
- **`skillsjars`** - shipped in some other artifact on the classpath.
- **`user-bundle`** - an app-managed source, reserved for content an app loads itself.

The line that decides trust is WHEN content arrives, not who wrote it. A skill that ships
inside a jar was fixed when the artifact set was assembled, reviewed and deployed, which is
exactly the standing of a prompt written as a string literal in an annotation: the same
review, the same release, the same blast radius. A dependency that would abuse a skill body
could as easily ship a class. So `builtin` and `skillsjars` differ in which jar carried the
file and in nothing that bears on trust, and today they are the only two that occur -
`SkillJarsLoader` is the sole caller of `SkillRegistry.register`, so every skill in a running
process is build-time content.

`user-bundle` names the case that would be different: content an app loads at runtime, from a
database row, an upload or a fetch, after the artifact set was fixed. Nothing produces it yet.
An app that opens that door is the one that needs guardrails on skill bodies and a reason to
log what it admitted, and its trust level is its own to assert - which is what that row has
always said.

`SkillJarsLoader` logs one line per registered bundle at INFO, naming the skill, the classpath
resource that carried it and the origin the bundle declared, followed by the count. That is an
inventory of what is live and where it came from, useful when a prompt in production is not
the one you expected. It is not a warning, because build-time content arriving at build time
is not an event. Nothing else reads origin; it is metadata for an app that wants it, and since
a bundle writes its own frontmatter it is a label rather than a finding.
Baseline framework guardrails (`SizeCapGuardrail`,
`ExfiltrationMarkerGuardrail`) ship for this content and run on every prompt produced,
external-skill bodies and descriptions included, once an app wires them through
`Prompts.addBaselineGuardrail`. They are opt-in: nothing registers them by default, and
`Prompts.removeBaselineGuardrail` takes one back off. An app that loads skilljars and
wires neither has no structural check on the text those bundles put into its prompts.

## Validation

`SchemaConformanceGuardrail` (in `ai.redouble.nucleo.tools.guardrails`) is the baseline
structural check:

- `name` non-blank.
- `body` non-null.
- `suggestedTools` entries warn (not fail) if unresolved against the `ToolHub` catalog -
  a Skill may be published before every suggested tool is admitted.

Framework does not claim semantic safety for any skill. Structural invariants are validated;
prompt injection resistance and output quality are not, whichever artifact the bundle came
from. For build-time content that is the same assurance a prompt literal in source carries.
For an app that loads skills at runtime under `user-bundle`, it is the point at which the app
owes its own validation, because the framework's structural gate is all it will get.
