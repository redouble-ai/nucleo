# Package: ai.redouble.examples.scoring

[Racing a grade](../benchmark/PACKAGE.md) benchmarked the order agent. The point of this
example is that nothing about that was particular to the order agent: any thinker goes
into a benchmark unchanged, and two setters sharpen what the judge measures. Here the
selecting agent from [Data the model cannot alter](../artifacts/PACKAGE.md) is raced
across its grade, exactly as written there.

<!-- sample: ScoreTheSelection.java#score -->
```java
JobDispatcher dispatcher = JobDispatcher.getInstance();
dispatcher.start();
try {
    Identifiable workflow = Job.workflow("you", "score");
    Benchmark<OrderQuestion, SelectedOrders> race = new Benchmark<>(workflow, () -> new SelectingAgent(workflow), 1);
    race.setInput(new OrderQuestion("Which orders of customer C-100 are delayed?"));
    race.setRubric("A good answer selects exactly the orders the question asks for,"
            + " by their references, and says which they are in one sentence.");
    // The selection is comparable in code: the overlap of the selected order numbers
    race.setScorer((reference, candidate) -> {
        if (reference == null || candidate == null) {
            return null;
        }
        Set<String> expected = selectedNumbers(reference);
        Set<String> selected = selectedNumbers(candidate);
        Set<String> union = new TreeSet<>(expected);
        union.addAll(selected);
        if (union.isEmpty()) {
            return null;
        }
        Set<String> shared = new TreeSet<>(expected);
        shared.retainAll(selected);
        return (double) shared.size() / union.size();
    });
    SelectedOrders reference = dispatcher.submit(race).get();
    System.out.println(reference.getSummary());
    System.out.println(race.report().table());
}
finally {
    dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
}
```

Reading it from the top:

**The thinker goes in as it is.** The factory builds a fresh `SelectingAgent` per run,
the same class the artifacts example wrote, with its tools, its prompt and its artifact
answer untouched. `Benchmark<OrderQuestion, SelectedOrders>` follows the thinker's own
input and output types.

**The rubric tells the judge what good means here.** The judge always compares each run
against the reference under its fixed scoring; the rubric adds what a good answer does for
this job in particular, beside the task itself.

**The scorer is a score in code.** Where an output can be compared with the reference's
without a model, a `Scorer` computes a number from 0 to 1 beside the judge's verdict. This
one reads the artifacts each run selected - the records themselves, from the registry, as
the caller always gets them - and scores the overlap of the selected order numbers. It
answers null when the pair cannot be compared, and the row simply carries no score there.
The report's rows then hold both judgments: the judge's, blind and under the rubric, and
the scorer's, exact and free.

Together the two catch what either alone misses: the scorer settles the selection with no
model in the loop, and the judge reads the summary sentence the scorer cannot.

## Next

[Your tools over MCP](../mcpserver/PACKAGE.md) begins the three MCP examples.
