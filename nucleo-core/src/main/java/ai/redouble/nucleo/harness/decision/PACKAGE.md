# Package: ai.redouble.nucleo.harness.decision

A decision model is the third kind of model the runtime calls, beside chat models and
embeddings models: it reads a state and typed questions about it, and answers each question
with a probability over the options you declared, generating no text
([What a decision model is](../../tools/deciding/PACKAGE.md)). This package is the
vocabulary of a decision: the questions you can ask, the request that carries them, and the
answers that come back. Every model of the class, TypeSafe's hosted Jev and the open
replicas such as Kev, reads and answers the same shapes, so code written against these types
runs on any of them.

## Asking

A question is one of three shapes, each built from the words the model reads (its
instructions) and the options you allow:

- **`Choice`**: pick one of the options you name. `Choice.of("Which team should handle this
  ticket?", "billing", "technical", "none")` names the options by key; the record's
  constructor takes a map from key to a description, for an option whose key alone does not
  say enough. Options keep the order you give them.
- **`Noul`**: the probability that a statement is true. `Noul.of("Does the ticket convey
  urgency?")`; the constructor can also describe what a yes and a no mean, both or neither.
- **`Score`**: a position on an ordered scale, lowest level first, at least two levels.
  `Score.of("How is the customer?", "calm", "frustrated", "very angry")`.

A `DecisionRequest` carries the state and the questions, each under an id of your choosing,
in a `LinkedHashMap` so the order is kept. The state is a string, or any structure (a map,
a list, a plain object), which is sent as JSON. The ids are yours: they key the request and
its answers on the wire, and the model is asked each question's instructions, never told
what to make of its id. The questions are answered independently of one another against
the one state.

## Reading the answers

The answers come back on the `DecisionResponse` of the call, read by the id and the shape
you asked: `choice(id)`, `noul(id)`, `score(id)`. Reading an id under another shape, or an
id never asked, is refused by name.

- A **`ChoiceAnswer`** holds the winning option (`choice()`), a probability per option in
  the order asked (`probabilities()`), and a confidence, how far the winner stands above an
  even spread. `probability()` is the winner's.
- A **`NoulAnswer`** is one number, `probability()`, the probability of yes.
- A **`ScoreAnswer`** holds a probability per level, the weighted position on the scale
  (`score()`, fractional between levels), the level descriptions and a confidence;
  `topLevel()` is the index of the most probable level.

Every probability is a number between 0 and 1, as the endpoint reported it. Two things are
yours to decide in code: the threshold a probability must clear before an action is taken,
and what to do with a question the state cannot answer, which is why a choice usually
carries a `none` option. Questions in one request do not see each other's answers, so a
statement and its negation need not sum to one; an invariant across questions is enforced
in code.

A question or answer that is malformed, a blank instruction or a choice with no options, is
refused where it is built, with an `IllegalArgumentException`. An endpoint's reply that
leaves out anything asked is refused as a failed call, never read as a partial answer.

## How it works inside

The types in full, the wire format every model of the class speaks and the full list of
refusals are in [Inside the decision wire](HARNESS_DECISION_INTERNALS.md), for those
working on the runtime itself.
