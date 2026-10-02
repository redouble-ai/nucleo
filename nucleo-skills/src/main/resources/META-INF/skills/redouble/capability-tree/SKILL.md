---
name: capability-tree
description: Render the recursive capability tree of a thinker as a markdown code-fenced ASCII diagram. Enumerates the caller's own tools and loaded skills, then invokes each child thinker-tool with the same enumeration query to collect their subtrees, skipping sub_thinker to avoid unbounded recursion. Use when a user asks "what tools and skills does this agent have" or "show the capability tree".
license: Apache-2.0
allowed-tools: []
metadata:
  author: Redouble AI
  origin: builtin
  bundle_id: ai.redouble.skills.capability-tree
  trigger_keyword: capabilities
---
# Capability Tree Enumeration

When admitted, respond to any request that matches "list your capabilities", "show the
capability tree", "what tools and skills are available", or equivalent by walking the
full capability graph rooted at the current thinker and rendering it as a single ASCII
tree inside a markdown code fence.

## Workflow

1. **Collect the root node (yourself).** Enumerate everything visible in YOUR current
   context:
   - Every tool in your tool list. Classify each as `tool` or `thinker` using the tool's
     declared type (`ToolType.THINKER` means thinker, anything else means tool).
   - Every skill in your loaded-skills list.

2. **Recurse into child thinkers, carrying the ancestor path.** For each tool
   classified as `thinker` EXCEPT `sub_thinker`:
   - **Before invoking**, check whether the child's thinker name or display name appears
     in your current ancestor path (the chain of thinker names from root down to
     yourself, inclusive). If it does, this is a CYCLE. Do NOT invoke the child. Emit
     `[cycle: <child-name>]` as the node in your tree and record the cycle path
     (see step 5).
   - Otherwise, invoke the child thinker-tool with an input whose `query` field is
     exactly:
     > `Enumerate your capability tree using the capability-tree skill. Ancestor path (do NOT expand any of these - emit '[cycle: <name>]' if you encounter one): [<comma-separated ancestor names, from root to me, inclusive>]. Return ONLY the indented markdown tree fragment rooted at yourself, no narration, no code fence.`
   - If the child thinker's typed input has required fields beyond `query`, fill them
     with minimal placeholder values (empty strings, nulls, or trivially valid examples
     per the field's type). The child's LLM will follow the query instruction; the typed
     fields go unused.
   - Receive the child's plain-text fragment. Scan it for any `[cycle: <name>]`
     markers; those are cycles the child detected under its subtree and they must be
     surfaced in step 5 alongside any you detected yourself.

3. **Splice, deduplicating shared agents.** Insert each child's fragment under its
   `▼` entry, reindenting so the child's root sits at one additional level. **The same
   agent's full subtree is rendered AT MOST ONCE in the entire output** - the first
   time it appears, expand fully; every subsequent occurrence renders as a single
   line `▼ AGENT_NAME  ↳ expanded above` with no children. (See "Dedup" section.)

4. **Render the tree.** Emit a single fenced code block containing the assembled tree.

5. **Report cycles prominently.** If ANY `[cycle: <name>]` marker exists anywhere in the
   assembled tree (whether you detected it at the root or a descendant surfaced it),
   emit a second block AFTER the tree fence with the cycle report. See the
   "Cycle reporting" section below for the exact format. If no cycles were detected,
   omit this block entirely.

## Recursion safety

Two termination mechanisms operate together. Both are mandatory.

### 1. Skip `sub_thinker` unconditionally

`sub_thinker` is the spawner for ad-hoc sub-agents; its children are unbounded.
Recursing through it does not terminate. It is always excluded from the tree,
regardless of any other rule.

### 2. Detect cycles via the ancestor path

A cycle exists when a thinker appears twice on a single path from root. This is
**distinct from the same thinker appearing in two different branches** - that is fine
(a child thinker can legitimately be reused by several parents).

Detection protocol:

1. The root thinker's ancestor path is `[<root-display-name>]`.
2. When the root invokes a child, the query carries the ancestor path as a string.
3. The child's ancestor path (for its own recursion) is the received list with its own
   name appended: `[root, me]`.
4. Before invoking ANY child, a thinker checks: does this child's name (or display
   name) appear in my current ancestor path? If yes, the invocation would loop.
   Emit `[cycle: <child-name>]` in place of the subtree and do NOT invoke.
5. If no, invoke with the extended ancestor path passed in the query.

The ancestor path is carried via the query string - there is no framework-level
mechanism for it. Every thinker must faithfully append its own name before forwarding
to children. Failure to append risks missing a cycle that spans more than two levels.

### Cycle reporting

After the tree code fence, if any `[cycle: ...]` markers appear in the tree (at any
depth), emit this block verbatim with the cycle list filled in:

```markdown
> **CIRCULAR DEPENDENCIES DETECTED**
>
> The following cycles were found and truncated in the tree above. Fix these in the
> tool graph - they would prevent termination for any traversal, not just this one.
>
> - `A -> B -> A`
> - `A -> B -> C -> B`
> - `A -> D -> E -> F -> D`
```

Each bullet shows the full path from root to the cycling node, with `->` separators
(ASCII arrow, NOT Unicode `→`). The path ends with the repeat-offender name as the
last element. If the same cycle was reported by multiple children, deduplicate by
canonical path.

This warning block is the ONLY prose allowed in your output. It follows the tree fence,
separated by one blank line. If there are no cycles, the entire block is omitted and
the tree fence is your whole answer.

## Tree rendering

Use Unicode box-drawing characters for the tree structure:

- `├── ` before a non-last sibling
- `└── ` before a last sibling
- `│   ` (vertical bar + 3 spaces) to continue past a non-last branch
- `    ` (4 spaces) to continue past a last-sibling branch

Node-line conventions, designed so agents are visually unmistakable when scanning a
deeply-nested tree:

| Node type | Prefix on the name line | Name format |
|---|---|---|
| Coded tool | `[T]` | snake_case lowercase as declared |
| Loaded skill | `[S]` | kebab-case lowercase as declared |
| Thinker / agent | `▼` (no badge - the arrow IS the badge) | UPPERCASE_WITH_UNDERSCORES |

Ordering within any node's children, in this exact order:

1. Coded tools (`[T]`) first, alphabetical by tool name.
2. Child agents (`▼`) second, alphabetical by name (uppercased for display).
3. Loaded skills (`[S]`) last, alphabetical by skill name.

Insert a blank continuation line (`│   ` for non-last, `    ` for last) between
groups - i.e. one blank line after the last tool, one blank line after the last
agent. This visual breathing room is what keeps the tree readable once nesting
goes more than two levels deep.

The root line has no box-drawing prefix - just the root agent's UPPERCASE name on
its own line.

## Dedup: agents that appear in multiple branches

Real tool graphs have shared dependencies (e.g. `LITERATURE_AGGREGATOR_AGENT` is
used by both `PHARMACOLOGIST` and `PATENT_ADVISOR`). Expanding the same agent's
full subtree under every parent makes the tree explode and obscures structure.

**Rule: each agent's subtree is expanded AT MOST ONCE in the entire output, on its
first occurrence. Subsequent occurrences render as a single line with no children:**

```
├── ▼ LITERATURE_AGGREGATOR_AGENT  ↳ expanded above
```

The `↳ expanded above` annotation is literal. Don't paraphrase it; the dev reading
the output relies on the exact string to grep for cross-references.

This is **not** the cycle-detection rule (see "Recursion safety"). Cycles get
`[cycle: NAME]` and the prominent warning block. Mere shared dependencies (same
agent appearing in two different non-overlapping paths from root) get the
`↳ expanded above` annotation and no warning - they are healthy reuse.

When the LLM at the root receives a child fragment that itself contains the same
agent's full subtree, the root must NOT re-expand. Track expanded-agent names as
you splice; second occurrence and onward become the one-liner.

## Output shape

```
ROOT_AGENT_NAME
├── [T] tool_name_1
├── [T] tool_name_2
│
├── ▼ FIRST_CHILD_AGENT
│   ├── [T] inner_tool
│   │
│   ├── ▼ NESTED_AGENT
│   │   ├── [T] nested_tool
│   │   └── [S] some-skill
│   │
│   └── [S] some-skill
│
├── ▼ SECOND_CHILD_AGENT
│   ├── [T] another_tool
│   │
│   ├── ▼ FIRST_CHILD_AGENT  ↳ expanded above
│   ├── ▼ NESTED_AGENT  ↳ expanded above
│   │
│   └── [S] capability-tree
│
├── [S] capability-tree
└── [S] some-skill
```

See `examples.md` for worked examples over an illustrative research-agent graph.

## Anti-patterns

- **Do NOT narrate.** No "I will now walk the tree", no "Here are the capabilities".
  Output the fenced code block and nothing else.
- **Do NOT invent children.** If a child thinker fails to respond (error, timeout), emit
  `[unavailable: <name>]` as the node and move on. Don't fabricate what the child would
  have reported.
- **Do NOT flatten.** A bullet list is not a tree. Use the box-drawing characters even
  if the tree is shallow.
- **Do NOT include `sub_thinker` as a child.** It's always present on every thinker and
  is explicitly excluded from the tree for recursion safety. Mention it only in a
  one-line footnote under the fence if you want to disclose its existence:
  `(sub_thinker omitted to prevent unbounded recursion)`.
- **Do NOT use `[A]` as the agent badge.** The convention is `▼ AGENT_NAME` - the
  arrow IS the badge. `[A]` reads as just another type-tag and gets visually lost
  among `[T]` and `[S]` neighbors. The whole reason for the format is that agents
  should jump off the page.
- **Do NOT keep agent names in their original lowercase form.** The UPPERCASE
  rendering is part of what makes them prominent. Convert `pharma_advisor` to
  `PHARMA_ADVISOR` for display. The underlying tool name doesn't change; this is
  rendering only.
- **Do NOT re-expand a shared agent's subtree.** Track expanded agents by name as
  you splice fragments together. Second and later occurrences are
  `▼ AGENT_NAME  ↳ expanded above` one-liners. Re-expanding bloats the tree and
  obscures the actual structure.
- **Do NOT admit this skill into `SubThinker`.** The framework can admit skills into
  any thinker; loading `capability-tree` into `SubThinker` would be harmless but
  pointless, since `SubThinker` inherits its parent's tools dynamically - its tree
  changes per invocation.
