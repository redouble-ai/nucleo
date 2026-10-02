# Package: ai.redouble.examples.benchmark

Every agent in these examples declares a grade, the rung of model capability it needs, and
the deployment decides which model serves it. That leaves two questions a team has to answer
with evidence: is the grade right for this job, and which of the models at that grade does
the job best, for what cost and how fast?

A benchmark answers them by running the job itself. It runs the job once the way the
deployment would, on the model it serves the grade with, and keeps that run as the
reference. Then it runs the same job, with the same input, on every model of that grade the
deployment can call. A judge, the strongest model the deployment serves, compares each run
with the reference, and the benchmark reports every run's cost, speed and score side by
side.

This page races the agent from [Your first agent](../agent/PACKAGE.md) across its grade,
SMALL.

## The code

<!-- sample: RaceTheGrade.java#race -->
```java
JobDispatcher dispatcher = JobDispatcher.getInstance();
dispatcher.start();
try {
    Identifiable workflow = Job.workflow("you", "race");
    Benchmark<OrderQuestion, OrderAnswer> race = new Benchmark<>(workflow, () -> new OrderAgent(workflow), 1);
    race.setInput(new OrderQuestion("Where are my orders A-1002 and A-1005?"));
    OrderAnswer reference = dispatcher.submit(race).get();
    System.out.println(reference.getReply());
    System.out.println(race.report().table());
}
finally {
    dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
}
```

**The benchmark is a doer.** `Benchmark` is an orchestrator like the one in
[Your first doer](../doer/PACKAGE.md), and it runs other jobs. It takes a factory,
`() -> new OrderAgent(workflow)`, because every run needs a fresh agent, and it pins each
candidate run to its model. The `1` is how many times each model runs the job. Any job
whose model is chosen by grade can be raced this way: the factory builds a
`ModelDependentTool`, which every agent is, and so is a tool such as `QuickLLMQuestionTool`
from [Hello, model](../hello/PACKAGE.md).

**The same input for every run.** `race.setInput(...)` takes the agent's own input, a
customer's question, and every run gets it unchanged.

**It returns the reference's answer.** `get()` gives back what the reference run answered,
so a workflow that wraps one of its steps in a benchmark receives exactly what the step
would have returned on its own. The measurements come beside it: `race.report()` is the
report, and `table()` renders it as a table, one row per run with its model, calls, tokens,
latency, cost and the judge's score and reason, then the means per model. The more
providers hold a credential, the more models the grade has to race.

## How the judge scores

The judge sees each run in full: every tool the agent called, what the tool returned, and
the answer. It compares that with the reference run under one fixed rubric, up to 7 points
for accuracy and 3 for the work shown, and it is not told which model ran either side. A
candidate that skipped a lookup, or called a tool wrongly, loses at least 3 points even
when its reply reads well. The reference is the yardstick and is not scored.

A run that fails, or is refused by a spend cap as in
[A dollar cap on a workflow](../cap/PACKAGE.md), is a row that says so, and the benchmark
finishes on the runs it has.

A race needs at least one entry OF the grade to be callable. A deployment can serve SMALL
seats from a stronger grade's model and never hold a SMALL entry, and there the benchmark
refuses up front, naming the credentials that would open one, rather than racing nothing.

## Next

[Measuring the grade a job needs](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/tools/benchmark/PACKAGE.md)
covers the benchmark in full. The next example,
[Any thinker in a benchmark](../scoring/PACKAGE.md), races a different agent and adds a
score computed in code.
