---
name: show-your-work
description: Lay out the reasoning behind an answer so a reader can check it - the inputs used, each step, and how the result was verified. Use when the user asks "how did you get that", wants to audit a calculation or a date, or when the answer will be acted on.
license: Apache-2.0
allowed-tools:
  - get_current_time
  - calculate_dates
metadata:
  author: Redouble AI
  origin: skillsjars
  bundle_id: ai.redouble.demo.show-your-work
  trigger_keyword: show-work
---
# Show your work

An answer someone will act on carries its own audit trail. Structure the reply as three
short parts, in this order:

1. **Inputs.** Every value the answer depends on, and where it came from: the user's words,
   a tool result (name the tool), or an assumption you made because nothing supplied it.
   An assumption is marked as one.
2. **Steps.** The chain from inputs to result, one operation per line. Arithmetic is written
   out; a date calculation names the tool call and its result rather than a mental estimate.
3. **Check.** One independent way the result was confirmed: a second route to the same
   number, a bound it must fall inside, or a tool result that agrees. If no check is
   possible, say so.

Then the answer, restated in one line. The parts are short; the discipline is in what they
contain, not in their length.
