# Package: ai.redouble.examples.cap

An agent decides for itself how many model calls it makes, and a workflow can start as many
agents as its data calls for. The bill follows those decisions. A spend cap turns the bill
into a limit the application sets: this piece of work may spend one cent, and once one cent
is committed, no new model work starts.

Nucleo checks the cap before a job runs. Every workflow, the unit of work named by
`Job.workflow` in [Your first tool](../tool/PACKAGE.md), can carry a cap in a currency. As a
job is about to start, the runtime works out the most its model calls may cost and refuses
the job if that would take the workflow past its cap. The refused job never sends a request,
and nothing already running is stopped.

This page runs the agent from [Your first agent](../agent/PACKAGE.md) again and again inside
one capped workflow until the cap refuses a run.

## The code

<!-- sample: SpendCap.java#cap -->
```java
JobDispatcher dispatcher = JobDispatcher.getInstance();
dispatcher.start();
CostLedger ledger = new CostLedger();
dispatcher.subscribe(ledger, JobEvent.class);
dispatcher.registerSpendGate(ledger);
try {
    Identifiable workflow = Job.workflow("you", "capped");
    ledger.cap(workflow.getWorkflowId(), new Cost(0.01, "USD"));
    for (int run = 1; ; run++) {
        OrderAgent agent = new OrderAgent(workflow);
        agent.setInput(new OrderQuestion("Where are my orders A-1002 and A-1005?"));
        try {
            dispatcher.submit(agent).get();
        }
        catch (ExecutionException e) {
            if (e.getCause() instanceof SpendCapExceededException refused) {
                System.out.println("run " + run + " refused: " + refused.getMessage());
                break;
            }
            throw e;
        }
        System.out.println("run " + run + " done, spent so far " + ledger.spent(workflow.getWorkflowId(), "USD"));
    }
}
finally {
    dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
}
```

The cap is one US cent; raise it to watch more runs pass before the wall.

## The ledger

`CostLedger` keeps the accounts. As jobs run, the dispatcher publishes events about them,
and `dispatcher.subscribe(ledger, JobEvent.class)` hands the ledger every one. When a job
finishes, the ledger prices each model call it made from the prices in the catalog, and
keeps the sums per workflow, per model and per call. `ledger.spent(...)` reads a workflow's
spend so far in one currency.

## The cap

`dispatcher.registerSpendGate(ledger)` makes the ledger the check every job passes before it
starts, and `ledger.cap(workflowId, new Cost(0.01, "USD"))` sets the workflow's limit.

The check works on reservations. Before a job starts, the runtime counts the input of its
model calls and adds the output its declaration allows, such as the `OutputSize.COMPACT` of
`OrderAgent`, and the ledger prices that. A job is refused when the workflow's spend so far,
plus what its running jobs have reserved, plus this job's reservation, would pass the cap.
Counting what is still running keeps a workflow that starts many jobs at once from slipping
past the cap before the first of them is priced.

Each run here finishes and adds its cost. The first run whose reservation no longer fits is
refused: `get()` throws an `ExecutionException` caused by `SpendCapExceededException`, whose
message names the workflow's spend, what is in flight, the cap and the reservation that would
have passed it.

The cap limits reservations, which are the runtime's own count of the input and the output
the declaration allows. A provider counts tokens its own way, so its invoice can differ
from the reservations call by call.

## Money nobody can state

A cap is in one currency, and the runtime never converts between currencies. Under a cap, a
job whose model is priced in a currency the workflow has no cap for is refused, and so is a
job whose model the catalog gives no price at all: a spend nobody can state cannot be
admitted.

## Next

[Observers, cost and the spend cap](../../../../../../../../nucleo-core/src/main/java/ai/redouble/nucleo/harness/observability/PACKAGE.md)
covers the ledger in full. The next example, [One decision](../decision/PACKAGE.md), uses a
different kind of model.
