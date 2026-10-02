# ai.redouble.nucleo.prompt.skill

A system prompt carries what an agent must always know. Much of what an agent could use is
worth carrying only sometimes: how to write a triage note, the house style for a report,
the worked example for one kind of request. Written into the system prompt, all of it rides
every call, costs tokens on every call, and dilutes the instructions that always matter.

A skill is such a piece of instruction as a named unit: a name, a description saying when
it applies, and a body with the instructions themselves. Skills live in a registry, an
agent is offered a catalog of them, and a skill enters the conversation only when it is
needed. Once admitted, its text rides the preamble of every later call in that
conversation, cached the way the provider caches its system prompt. The format is the
Agent Skills convention, so a skill written for Claude works here as it is.

## Where skills come from

Skills are found on the classpath. Any jar with this layout contributes its bundles, and
`SkillRegistry` finds them the first time anything asks:

```
META-INF/skills/
  claim-triage/                               <- flat
    SKILL.md
    templates/worked-example.md
  anthropics/skills/mcp-builder/              <- nested (SkillsJars-style org/repo/skill)
    SKILL.md
    reference/tool-design.md
```

`SKILL.md` opens with YAML frontmatter, and the markdown after it is the skill's body:

```
---
name: claim-triage
description: How to triage a hospital indemnity claim; use when a claim arrives unassessed.
---
Read the claim's diagnosis codes first...
```

`name` defaults to the directory name; `description` is required and tells the model when
the skill applies, so it is worth writing the way a tool's description is. Every other file
in the skill's directory becomes a named resource of the skill. The full frontmatter
(license, author, suggested tools, a trigger keyword) is in
[Inside skills](PROMPT_SKILL_INTERNALS.md).

Because a skill ships in a jar, it arrives with the release, reviewed like any code in that
jar. The instructions of an agent stop being one string in one class and become content a
team curates: written once, shared across agents and applications, versioned with the
artifact that carries them.

## How an agent gets a skill

A thinker declares each of the two paths separately:

- **Always on.** `declareDefaultSkills()` returns the names of skills the thinker attaches
  before its first call. The model has no say; this is for a skill the agent always runs
  with.
- **Admitted by the model.** `declareCompatibleSkills()` returns a `SkillSelector`: named
  skills, every skill of a bundle, or everything the registry holds. Each turn the model
  is offered the `request_skill` tool whose input schema lists exactly that catalog, each
  entry with the skill's own description, so the model admits a skill by name from what it
  was offered and can name nothing else. The demo's agent runs this way: asked to answer
  "briefly", it reads the catalog, admits `concise-answers`, and its answers follow that
  skill from then on.

An application can gate the model's path: `ToolHub.registerSkillAdmission` attaches an
admission rule (for a thinker class and a skill name, glob or predicate) that runs before a
skill is admitted, and a rejected name goes back to the model with the reason. The text of
every skill passes the same guardrails as any prompt: wire
`Prompts.addBaselineGuardrail` and every produced prompt is checked, skill bodies and
descriptions included. Nothing is attached by default.

## How it works inside

The types, the full frontmatter mapping, where each provider places an admitted skill's
text and how it is cached, the origin metadata and what it does and does not say about
trust, and the structural validation are in [Inside skills](PROMPT_SKILL_INTERNALS.md),
for those working on the runtime itself.
