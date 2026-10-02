# Inside the benchmark

This page is for people working on the runtime itself. What the benchmark measures and how
to wrap a job in one are in [the guide](PACKAGE.md); the contract is also stated in
[tools/PACKAGE.md](../PACKAGE.md), "Benchmark: any model-dependent job, raced across its
grade". This page names the parts.

| Class | Role |
|-------|------|
| `Benchmark<I,O>` | The doer: one reference run on the picker's choice for the grade, first and alone (its full run is the ground truth), then `runs` runs per candidate entry, each pinned and identical otherwise; the reference is the yardstick and is not judged. Returns the reference's output; the measurements go sideways as a `BenchmarkReport` on the job's metadata under `Benchmark.REPORT`, in the log as a table, and from `report()` after the run. `candidates(grade)` is the callable-entry filter (open, provider configured, data-share posture provisioned) - the test the default picker applies before it serves an entry, less the compliance envelope, which the resolution gate still applies to every pinned run. Every run and judge is submitted with metadata naming its place in the race (`RUN_ROLE` of `REFERENCE`, `CANDIDATE` or `JUDGE`; `RUN_MODEL`, the candidate's id; `RUN_NUMBER`), so an observer of the workflow's events attributes each event to a row - the run's own by the entries, its calls' by their parent chain. |
| `BenchmarkReport` | One row per run (model, success or the failure's reason, calls, iterations, tokens, latency, wall, cost with its currency, the judge's score, the scorer's), one summary of means per model, and `table()`, the coloured fixed-width rendering for a log line. |
| `JudgeTool` | The judge: `Grade.CEILING`, one call per candidate run, submitted the moment the run lands so the judges overlap the race, comparing the run in full against the reference run in full under one fixed rubric (`JudgeTool.INSTRUCTIONS`): 7 points accuracy - 7 matching the reference in substance, 6 or 6.5 a defensible different reading of the task, 3 to 5 partly right, 0 to 2 wrong, a tool called wrongly, skipped or misread costing at least 3 - and 3 points shown work. The judge calls no tool and works nothing out itself: the reference's transcript is the truth. Blind - nothing in its input says which model ran either side. A test overrides `Benchmark.newJudge()` to judge without a transport. |
| `JudgeInput` / `JudgeVerdict` | What one judge sees (the task as the job received it, the caller's extra criteria, the reference run - the thinker's `transcript()`, every tool call and result, then its answer - and the candidate run the same way) and what it returns (accuracy of 7 and reasoning of 3 in half points, the tool fault if any, a reason); the score of 10 is the two added, by the benchmark. |
| `Scorer<O>` | A score in code, for outputs comparable to the reference without a model; `[0, 1]` or null when the pair cannot be compared. A benchmark may run both the judge and a scorer. |

Failure discipline: a run that fails or is refused at the cap is a row that says so, and
the benchmark finishes on what it has; a judge that fails, or scores outside the rubric,
costs its own run's score, with the reason on the row, and the report names a judge
failure only when every judge failed; a reference that fails leaves every run unjudged,
the reason on the report, and fails the benchmark the way the wrapped job would have
failed, after the table is logged.

`BenchmarkTest` drives all of it through the dispatcher on a job that answers with the
model it was served.
