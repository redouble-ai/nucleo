# Inside the decision wire

This page is for people working on the runtime itself. How to ask a decision model a
question and read its answer are in [the guide](PACKAGE.md); this page holds the types in
full, the codec every model of the class speaks, and every refusal of the vocabulary.
TypeSafe's Jev defined the shape and its wire; the open replicas served from a machine the
deployment owns (Kev, Nimble, OpenJev) reproduce both, which is why the codec lives here in
the runtime and a provider module carries only its endpoint. The client family itself
(`DecisionClient`, `AbstractDecisionClient`, `DecisionResponse`) is in `harness.llm` beside
the other two, and `tools.deciding.DecisionCall` is the job every decision is.

## What is here

| Type | Role |
|---|---|
| `Question` | Sealed: the three shapes a question takes. `instructions()` are the words the model reads, never blank; the id a question is asked under is the caller's handle, keying the wire request and its answers, and is nothing the model is asked about. |
| `Choice` | Pick one of the options the caller names: option key to description (null when the key says enough), order kept, at least one option, every key non-blank. `Choice.of(instructions, keys...)` for keys alone. |
| `Noul` | The probability that a statement is true. What a yes and a no mean may be described, both or neither. `Noul.of(instructions)`. |
| `Score` | A position on an ordered scale the caller describes, lowest level first, at least two levels, every level described. `Score.of(instructions, levels...)`. |
| `Answer` | Sealed: the three shapes an answer takes, one per question shape. Every probability is a number between 0 and 1 as the endpoint reported it, a number the caller thresholds and never a measured rate of being right. |
| `ChoiceAnswer` | The winning option (one of the options answered), a probability per option in the order asked (every option has one), and the endpoint's confidence, which is arithmetic on the distribution (how far the winner stands above uniform). `probability()` is the winner's. |
| `NoulAnswer` | One number, the probability of yes. No separate confidence: the number already is the model's belief. |
| `ScoreAnswer` | A probability per level in the order asked, their weighted position on the scale (fractional between levels, within the scale), the level descriptions, and the confidence. `topLevel()` is the most probable level's index. |
| `DecisionRequest` | A state (text, or a structure the wire renders as JSON; never null) and the questions by id, at least one, every id non-blank, in the order asked. The questions are answered independently against the one state. |
| `SystemOneWire` | The codec: `encode(wireModelId, request)` is the request body every endpoint of the class reads; `decode(body, request)` reads the answers back strictly against what was asked. `PATH` is `/v1/systemone`. |

## The wire

Encoding renders the state as the request gave it, a string as text and anything else as
the JSON the runtime's serializer writes, and each question under its id in its shape: a
choice as a `criteria` object of option to description, a noul with an optional `criteria`
of what true and false mean, a score as a `criteria` array of levels. A null wire model id
leaves the `model` field out: that is the body as counted for a reservation before the
entry is resolved, which differs from the body sent by that one field
(`SystemOneWireTest`).

Decoding is lenient about the extra and strict about the missing, the rule the
OpenAI-dialect clients follow. Unknown fields anywhere are ignored (a latency figure, a
request id, an answer to a question nobody asked), because the endpoints of this class
diverge in exactly those. Every question asked is answered, in the shape of the question,
with the option keys the question named: a body that is not a JSON object or has no
`answers` object, a choice whose winner was not among the options or whose `choice` or
`probabilities` is missing, a probability missing for an option or a level, a noul that is
not a number, a score whose `score` position is missing or not a number, a confidence
absent from a choice or a score, a probability outside 0 to 1, is a body that is not an
answer, refused as an `IOException` naming what was missing and never quoting the state.
The endpoint's `usage.input_tokens` and `model` are read when present and null when not.

## Refusals

A call that answered nothing refuses with its reason when an answer is read
(`DecisionResponseTest`). Every refusal of the vocabulary itself (a blank instruction, a
choice without options or with a blank key, a noul describing only one side, a score of one
level or with a blank level, a request without a state or a question or with a blank id, a
choice answer whose winner is not among the options or whose probability or confidence is
missing or outside 0 to 1, a score answer with a probability count other than its level
count or a position off its scale) is an `IllegalArgumentException` at construction
(`SystemOneWireTest`).
