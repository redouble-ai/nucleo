# Package: ai.redouble.nucleo.tools.benchmark

A job declares the [grade](../../harness/models/PACKAGE.md) of model it needs, and the
grade is a guess until someone measures it. Several models of the same grade can
differ several times over in price and speed, and one of them may do the job as well as the
model the deployment pinned, or skip a tool call the job depends on. Reading a few answers
by hand does not settle it.

The benchmark settles it by running the job itself, on your input, on every model of its
grade, and scoring every run against one reference run. It answers the question per job,
for the job you actually run, with the cost, the time and the quality of each model side
by side.

## What it does

`Benchmark<I,O>` wraps any job that takes a seat at a model: a thinker, or a tool whose
work is one model call (together, the `ModelDependentTool`s of
[Tools, thinkers and doers](../PACKAGE.md)). Given the job's input, it:

1. **Runs the reference.** The job runs once exactly as the deployment would run it, on the
   model the picker serves for its grade. Its whole run, every tool it called with what the
   tool returned, and its answer, is the yardstick every other run is measured against.
2. **Races the grade.** The job then runs on every entry of its grade the deployment can
   call, the reference's own entry among them when it is of that grade, each run pinned to
   its entry and otherwise
   identical, all submitted at once and paced by admission like any other work.
3. **Judges each run blind.** As each run lands, a judge running on the strongest model the
   deployment serves (`Grade.CEILING`) compares it with the reference, in full, under one
   fixed rubric: up to 7 points for accuracy, of which a tool called wrongly, skipped or
   misread costs at least 3, and up to 3 for shown work. Nothing the judge reads names the
   model on either side.

The benchmark returns what the reference returned, so a workflow that wraps a step in a
benchmark gets the answer it would have got without one. The measurements come back beside
it, as a `BenchmarkReport` from `report()`: one row per run with the model, whether it
succeeded, its calls, tokens, latency, wall time, cost in the entry's currency and the
judge's score and reason, then the means per model. The same report is logged as a table
and put on the benchmark job's metadata under `Benchmark.REPORT`.

## Using it

`new Benchmark<>(parent, factory, runs)` takes a factory that builds a fresh instance of the
job for every run, and how many times each model runs it; `setInput` gives it the job's
input. Three setters shape a race:

- `setCandidates(list)` races the entries you name, where the default is every callable
  entry of the grade (`Benchmark.candidates(grade)`).
- `setRubric(text)` tells the judge what a good answer does for this job, beside the task
  itself.
- `setScorer(scorer)` adds a score computed in code, for outputs that can be compared with
  the reference directly, an extraction or a classification: a `Scorer<O>` returns a number
  from 0 to 1, or null when the pair cannot be compared. The judge still runs beside it.

A run that fails is a row that says why, and the benchmark finishes on the rest. When the
reference fails there is nothing to judge against: the table is logged and the benchmark
fails the way the job would have.

## How it works inside

The classes, what each judge reads and returns, and how runs and judges are labelled on the
message bus are in [Inside the benchmark](TOOLS_BENCHMARK_INTERNALS.md), for those working
on the runtime itself.

> **Example:** [Racing a grade](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/benchmark/PACKAGE.md) -
> the order agent raced across every callable entry of its grade and judged blind against
> the reference run. [Any thinker in a benchmark](../../../../../../../../../nucleo-examples/src/main/java/ai/redouble/examples/scoring/PACKAGE.md)
> races the selecting agent with a rubric and a scorer in code.
