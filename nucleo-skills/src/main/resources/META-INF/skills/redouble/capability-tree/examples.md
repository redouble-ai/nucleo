# Capability Tree - Worked Examples

All examples use the rendering convention: agents render as `▼ UPPERCASE_NAME`
(no `[A]` badge), tools as `[T] snake_case`, skills as `[S] kebab-case`. Blank
continuation lines separate tool / agent / skill groups. The agents and tools are an
illustrative research graph, not classes this runtime ships.

## Example 1 - Leaf thinker (no child thinkers)

A structural-biology thinker, `PDB_EXPERT`, exposes three coded tools and no child thinkers. Admitted with the
`capability-tree` skill, it emits:

```
PDB_EXPERT
├── [T] current_time
├── [T] pdb_fetch
├── [T] pdb_search
│
└── [S] capability-tree
```

No recursion happens because none of the tools are thinkers.

## Example 2 - Coordinator thinker

A coordinator, `PHARMACOLOGIST`, owns a mix of coded tools and child thinkers. Expected shape
with the dedup rule applied:

```
PHARMACOLOGIST
├── [T] brave_search
├── [T] current_time
├── [T] web_search_exa
│
├── ▼ LITERATURE_AGGREGATOR_AGENT
│   ├── [T] biorxiv_fetch
│   ├── [T] biorxiv_search
│   ├── [T] current_time
│   ├── [T] pubmed_fetch
│   ├── [T] pubmed_search
│   │
│   └── [S] literature-synthesis
│
├── ▼ MEDICINAL_CHEMIST
│   ├── [T] chembl_search
│   └── [T] pubchem_search
│
├── ▼ STRUCTURAL_BIOLOGIST
│   ├── [T] pdb_fetch
│   └── [T] pdb_search
│
├── [S] capability-tree
└── [S] literature-synthesis
```

Each child agent was invoked with the recursion query and returned its own
subtree. Children appear after the coded-tool group, separated by a blank
continuation line.

## Example 3 - Shared dependency (the dedup case)

`PHARMA_ADVISOR` coordinates several child agents that themselves share
dependencies. `LITERATURE_AGGREGATOR_AGENT` is used by both `PHARMA_ADVISOR`
directly and indirectly via `PHARMACOLOGIST` and `PATENT_ADVISOR`. It expands
exactly ONCE - on its first appearance - and is referenced by name everywhere
else:

```
PHARMA_ADVISOR
├── [T] brave_web_search
├── [T] get_current_time
│
├── ▼ BIOINFORMATICIAN
│   ├── [T] chembl
│   ├── [T] uniprot
│   │
│   ├── ▼ LITERATURE_AGGREGATOR_AGENT
│   │   ├── [T] fetch_biorxiv_preprint
│   │   ├── [T] fetch_pubmed_articles
│   │   ├── [T] search_biorxiv
│   │   └── [T] search_pubmed
│   │
│   ├── ▼ STRUCTURAL_BIOLOGIST
│   │   ├── [T] pdb_fetch
│   │   └── [T] pdb_search
│   │
│   └── [S] literature-synthesis
│
├── ▼ CLINICAL_SCIENTIST
│   ├── [T] clinicaltrials_fetch
│   ├── [T] clinicaltrials_search
│   ├── [T] dailymed_fetch
│   ├── [T] dailymed_search
│   │
│   ├── ▼ LITERATURE_AGGREGATOR_AGENT  ↳ expanded above
│   │
│   └── [S] literature-synthesis
│
├── ▼ LITERATURE_AGGREGATOR_AGENT  ↳ expanded above
│
├── ▼ PATENT_ADVISOR
│   ├── [T] epo
│   ├── [T] uspto_odp
│   │
│   ├── ▼ LITERATURE_AGGREGATOR_AGENT  ↳ expanded above
│   │
│   └── [S] literature-synthesis
│
├── ▼ PHARMACOLOGIST
│   ├── [T] brave_search
│   ├── [T] web_search_exa
│   │
│   ├── ▼ LITERATURE_AGGREGATOR_AGENT  ↳ expanded above
│   ├── ▼ MEDICINAL_CHEMIST
│   │   ├── [T] chembl_search
│   │   └── [T] pubchem_search
│   │
│   ├── ▼ STRUCTURAL_BIOLOGIST  ↳ expanded above
│   │
│   └── [S] literature-synthesis
│
├── [S] capability-tree
└── [S] literature-synthesis

(sub_thinker omitted to prevent unbounded recursion)
```

Notice: `LITERATURE_AGGREGATOR_AGENT` and `STRUCTURAL_BIOLOGIST` appear multiple
times but each subtree only expands once. Also notice the visual: agent lines
stand out from the densely-packed `[T]` lists thanks to the `▼` arrow,
UPPERCASE name, and surrounding blank lines.

## Example 4 - Error handling

If `MEDICINAL_CHEMIST` failed to respond (timeout, internal error), its node
renders inline; the rest of the tree still completes:

```
PHARMACOLOGIST
├── [T] brave_search
├── [T] current_time
│
├── ▼ LITERATURE_AGGREGATOR_AGENT
│   └── ...
│
├── ▼ [unavailable: MEDICINAL_CHEMIST]
├── ▼ STRUCTURAL_BIOLOGIST
│   └── ...
│
└── [S] capability-tree
```

The failed node is marked inline; the rest of the tree still renders.

## Example 5 - Cycle (immediate)

If a configuration error put `AGENT_A` and `AGENT_B` into a two-level cycle, the
tree breaks the cycle on the second occurrence AND a prominent warning block
follows:

```
AGENT_A
├── ▼ AGENT_B
│   └── ▼ [cycle: AGENT_A]
│
└── [S] capability-tree
```

> **CIRCULAR DEPENDENCIES DETECTED**
>
> The following cycles were found and truncated in the tree above. Fix these in
> the tool graph - they would prevent termination for any traversal, not just
> this one.
>
> - `AGENT_A -> AGENT_B -> AGENT_A`

## Example 6 - Cycle (multi-level)

A more insidious case: `AGENT_A -> AGENT_D -> AGENT_E -> AGENT_F -> AGENT_D`.
The cycle does not involve the root, but `AGENT_D` appears twice on the path
from root through `AGENT_F`. `AGENT_F` detects the cycle because `AGENT_D` is in
its ancestor path:

```
AGENT_A
├── ▼ AGENT_D
│   └── ▼ AGENT_E
│       └── ▼ AGENT_F
│           └── ▼ [cycle: AGENT_D]
│
└── [S] capability-tree
```

> **CIRCULAR DEPENDENCIES DETECTED**
>
> The following cycles were found and truncated in the tree above. Fix these in
> the tool graph - they would prevent termination for any traversal, not just
> this one.
>
> - `AGENT_A -> AGENT_D -> AGENT_E -> AGENT_F -> AGENT_D`

## Example 7 - Multiple independent cycles

If two unrelated cycles exist in the same tree, both are reported:

```
ROOT_AGENT
├── ▼ LEFT_BRANCH
│   └── ▼ [cycle: LEFT_BRANCH]
│
└── ▼ RIGHT_BRANCH
    └── ▼ HELPER_AGENT
        └── ▼ [cycle: RIGHT_BRANCH]
```

> **CIRCULAR DEPENDENCIES DETECTED**
>
> The following cycles were found and truncated in the tree above. Fix these in
> the tool graph - they would prevent termination for any traversal, not just
> this one.
>
> - `ROOT_AGENT -> LEFT_BRANCH -> LEFT_BRANCH`
> - `ROOT_AGENT -> RIGHT_BRANCH -> HELPER_AGENT -> RIGHT_BRANCH`
