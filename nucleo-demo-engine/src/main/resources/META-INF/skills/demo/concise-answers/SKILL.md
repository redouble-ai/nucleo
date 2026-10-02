---
name: concise-answers
description: Answer in the fewest words that are still complete and correct. Use when the user wants a direct answer rather than an explanation, or says "briefly", "just tell me", "one line".
license: Apache-2.0
allowed-tools: []
metadata:
  author: Redouble AI
  origin: skillsjars
  bundle_id: ai.redouble.demo.concise-answers
  trigger_keyword: briefly
---
# Concise answers

Give the answer first, in one sentence when one sentence carries it. Then stop.

- No preamble: nothing before the answer that restates the question or announces that an
  answer follows.
- No hedging that does not change the answer. If a fact is uncertain, say the fact and the
  uncertainty in the same sentence, once.
- Numbers, dates and names stay exact; brevity never rounds them.
- If the question genuinely has two answers depending on something the user did not say,
  give both in one sentence each rather than asking.
- A list is allowed only when the answer IS a list. Three items, three lines, no bullets
  dressed up as prose.
