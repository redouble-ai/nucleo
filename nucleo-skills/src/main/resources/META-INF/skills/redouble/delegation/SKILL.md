---
name: delegation
description: How to decompose and dispatch work. One decision drives it - are you splitting the QUESTION (different angles on one subject) or the DATA (the same operation over every item of a list)? Split the question with sub_thinker; process the data with a fan-out tool from your palette - never loop over items yourself, never hand a whole list to one sub-agent, never paste list contents (pass refs). Load the body for why this exists, reading list digests, and when not to delegate.
license: Apache-2.0
allowed-tools: []
metadata:
  author: Redouble AI
  origin: builtin
  bundle_id: ai.redouble.skills.delegation
  trigger_keyword: delegation
---
# Delegation and Decomposition

## Why this exists

A model given a long list in one context and told "do this to each item" fails
quietly: it skips items, duplicates them, and drifts - the standard it applied to
item 1 has subtly shifted by item 20. The output looks fine and is the wrong
length, and you cannot tell. So you never iterate a list in your own context.
Reliable iteration is infrastructure: a fan-out tool enumerates the list in code,
runs each item in its own isolated call, and returns a complete same-length result
or fails loudly.

## The one decision: question or data?

Before delegating, ask what you are splitting.

- Splitting the **QUESTION** - different angles on one subject. "Plan a weekend
  trip - the flights, the lodging, and the things to do" is three angles on one
  trip. Spawn one `sub_thinker` per angle, each with its own objective and the
  `exclusions` field set to the other angles' topics so the sub-agents do not
  converge on the same ground.
- Splitting the **DATA** - the same operation over every item of a list. "Translate
  each paragraph", "look up the time zone of each city", "label each of 200 reviews
  positive or negative" is one operation over N items. Use a fan-out tool: a tool
  in your palette that takes a list ref and runs one worker per item. If none is in
  your palette, look for one in the `request_tools` catalog. Never use `sub_thinker`
  for this - not one sub-agent per item, not one sub-agent for the whole list.

If a task is both - "for each of several cities, research its housing, its weather,
and its commute" - split the data first (a fan-out over the cities) and let each
item's worker be a `sub_thinker` that splits the question.

If no fan-out tool is available, say so in your answer and work only on what one
context can judge reliably; do not iterate a long list yourself and present the
result as complete.

## You plan over refs; you never carry the data

- Never call a tool once per item in a loop of your own.
- Never author, paste, or re-emit list contents as JSON in arguments or answers.
  Pass the ref; the framework reads it.
- Never reproduce attached or retrieved content of any size in an argument - pass a
  ref to the source.
- A final answer over bulk results is a ref to a result list, not the items spelled
  out token by token.

## Reading a list

A list reaches you as a digest: `artifact_ref`, `iterand_type`, `count`, and a head
`sample`. The sample shows the item shape; it is NOT the whole list. Counts and
shape come from the digest; facts about individual items require fanning out over
the list or fetching one item by ref (`get_artifact_field`,
`search_artifact_content`).

## When NOT to delegate

- A question answerable from the digest alone (count, item shape, a rough read of
  the sample) needs no tools.
- A single judgment on one or two artifacts already in context is your own
  reasoning - fetch full content by ref first (`get_artifact_field`) if the
  judgment needs it.
- Do not spawn a sub-agent to do what one tool call or your own reasoning does.
