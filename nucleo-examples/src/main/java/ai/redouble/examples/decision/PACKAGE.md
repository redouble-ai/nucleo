# Package: ai.redouble.examples.decision

Much of what an application asks a model is a verdict among options the code already knows:
which team should take this ticket, is it urgent, how upset is the customer. A chat model answers such
a question in text, and code then has to read a team name out of a sentence and trust that
the model picked one of the teams that exist.

A decision model is a different kind of model, built for exactly these questions. You give
it a state, the text to judge, and typed questions about it, each with the options your code
declared. It writes no text. It answers each question with a probability for every option,
so every answer is one of your options, and the numbers show how clear-cut the call was.
Code branches on the answer directly. Decision models are also cheap and fast: output
costs nothing, every question in a request is answered in one round trip, and a small one
runs on your own machine.

This page puts one support ticket to a decision model as three questions.

## The code

<!-- sample: TriageTicket.java#decide -->
```java
JobDispatcher dispatcher = JobDispatcher.getInstance();
dispatcher.start();
try {
    String ticket = "I was charged twice for order A-1002 and it still has not arrived. Fix this today.";
    LinkedHashMap<String, Question> questions = new LinkedHashMap<>();
    questions.put("team", Choice.of("Which team should handle this ticket?", "billing", "shipping", "technical"));
    questions.put("urgent", Noul.of("Does the ticket convey urgency?"));
    questions.put("mood", Score.of("How is the customer?", "calm", "frustrated", "very angry"));
    DecisionCall call = new DecisionCall(Job.workflow("you", "triage"), new DecisionRequest(ticket, questions));
    DecisionResponse response = dispatcher.submit(call).get();
    ChoiceAnswer team = response.choice("team");
    System.out.println("team: " + team.choice() + " " + team.probabilities());
    System.out.println("urgent: " + response.noul("urgent").probability());
    ScoreAnswer mood = response.score("mood");
    System.out.println("mood: " + mood.levels().get(mood.topLevel()) + " " + mood.probabilities());
}
finally {
    dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
}
```

**Three kinds of question.** A question comes in one of three shapes, and the shape decides
what comes back:

- `Choice` picks one of named options. The answer, a `ChoiceAnswer`, has the winner,
  `choice()`, and a probability for each option, `probabilities()`.
- `Noul` is a statement that is true or false. The answer is one number, `probability()`,
  the probability that it is true.
- `Score` places the state on an ordered scale, lowest level first. The answer, a
  `ScoreAnswer`, has a probability for each level and `topLevel()`, the index of the most
  likely one.

**The request.** A `DecisionRequest` is the state, here the ticket text, and the questions.
Each question goes in under an id of your own (`team`, `urgent`, `mood`) that your code
reads the answer by and the model never sees. The questions go in a `LinkedHashMap`
because the order options are asked in can move the answer, and a fixed order makes a run
repeatable.

**The call.** `DecisionCall` is the job that asks, submitted like every job before it. Like
a call to a chat model, it is served by the deployment's decision model, priced, admitted
against that model's limits and recorded. When the catalog does not name which decision
model answers, the runtime takes the first one it can call and names its choice in the
log.

## What it answered

This program needs a decision model to be connected; step 1 of
[Set up and run the demo](../../../../../../../../AGENTS.md) says how. The one recorded here
is Kev, which runs on a 32 GB Apple Silicon Mac or a GPU box, and the shipped catalog carries
it as `kev-local`. Given the ticket "I was charged twice for order A-1002 and it still has
not arrived. Fix this today.", Kev-9B answered on 2026-09-27:

```
team: billing {billing=0.4863, shipping=0.4352, technical=0.0785}
urgent: 0.9693
mood: very angry [0.0168, 0.4557, 0.5275]
```

The ticket is about a double charge and a late delivery at once, and the answer says so:
billing and shipping nearly tied, technical far behind. A chat model asked to name a team
would have named one; the decision model shows how close the call was, and your code
decides what to do with a near tie.

## Next

[What a decision model is](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/tools/deciding/PACKAGE.md)
says when to use a decision model and when a chat model, and how to run a whole agent on
one. The next example, [Racing a grade](../benchmark/PACKAGE.md), measures which grade of
chat model a job needs.
