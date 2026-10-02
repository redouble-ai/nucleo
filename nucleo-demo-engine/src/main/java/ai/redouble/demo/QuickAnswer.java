/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.tools.builtin.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * The quick question's answer as the page shows it: the answer and the model's reasoning, the
 * catalog entry that answered and the model that served it, and what the call cost, priced from
 * the catalog like every other result on the page. Built from the calls the finished job made, as
 * its completion event carries them ({@link #capture}), so the cost is there when the answer is.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-27)
 */
public class QuickAnswer {
    private String answer;
    private String reasoning;
    private String model;
    private String servedModel;
    private Double cost;
    private String currency;

    /** One call the job made, as priced: its catalog entry, the model that served it, and its cost, null when the entry carries no price. */
    record Call(String model, String servedModel, Cost cost) {}

    /** How long the answer waits for its job's completion event once the job's result is in. */
    static final Duration COMPLETION_EVENT_WAIT = Duration.ofSeconds(5);

    /**
     * Captures the calls of one quick-question job from its completion event, which carries its
     * own copy of them. The job's context is no source: the dispatcher releases a job's recorded
     * calls from it once the job settles, and a caller that reads the context after the result
     * races that release and finds nothing. Opened before the job is submitted, so the event cannot
     * be missed; closed once the answer is built or the run failed.
     */
    public static Capture capture(JobDispatcher dispatcher, QuickLLMQuestionTool tool) {
        return new Capture(observer -> dispatcher.subscribe(observer, JobEvent.class), tool.getId());
    }

    /** The calls of one job, delivered by its completion event; see {@link #capture}. */
    public static final class Capture implements AutoCloseable {
        private final CompletableFuture<List<LLMResponse<?>>> calls = new CompletableFuture<>();
        private final MessageBus.Subscription subscription;

        /** Over the subscribing step, so a test can deliver the events itself. */
        Capture(Function<JobObserver<JobEvent>, MessageBus.Subscription> subscribe, String jobId) {
            subscription = subscribe.apply(new JobObserver<>() {
                @Override
                public Predicate<JobEvent> getPredicate() {
                    return event -> event instanceof JobCompletedEvent<?> && event.snapshot() != null && jobId.equals(event.snapshot().getJobId());
                }

                @Override
                public void observe(JobEvent event) {
                    calls.complete(((JobCompletedEvent<?>) event).getLlmResponses());
                }
            });
        }

        /**
         * The answer of the finished job, with the calls its completion event carried. The event is
         * published before the job's result is handed back, so it has arrived or is in flight; one
         * that does not arrive within {@link #COMPLETION_EVENT_WAIT} is a defect and fails the answer.
         */
        public QuickAnswer answer(QuickLLMQuestionOutput output) {
            return of(output, awaitCalls());
        }

        /** The calls the job's completion event carried, waiting up to {@link #COMPLETION_EVENT_WAIT} for it. */
        List<LLMResponse<?>> awaitCalls() {
            try {
                return calls.get(COMPLETION_EVENT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException e) {
                throw new IllegalStateException("The quick question settled without its completion event reaching the answer", e);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while the quick question's completion event was awaited", e);
            }
            catch (ExecutionException e) {
                throw new IllegalStateException("The quick question's completion event could not be read", e);
            }
        }

        @Override
        public void close() {
            subscription.unsubscribe();
        }
    }

    /** The answer of a finished job, with the calls it made. */
    public static QuickAnswer of(QuickLLMQuestionOutput output, List<LLMResponse<?>> responses) {
        List<Call> calls = new ArrayList<>(responses.size());
        for (LLMResponse<?> response : responses) {
            calls.add(new Call(response.getModel(), response.getServedModelId(), RunMeasure.price(response)));
        }
        return answered(output, calls);
    }

    /** The answer with the calls summed: the last call's entry and served model, and every call's cost, in the one currency they share. */
    static QuickAnswer answered(QuickLLMQuestionOutput output, List<Call> calls) {
        QuickAnswer quick = new QuickAnswer();
        quick.answer = output.getAnswer();
        quick.reasoning = output.getReasoning();
        Cost total = null;
        for (Call call : calls) {
            if (call.model() != null) {
                quick.model = call.model();
            }
            if (call.servedModel() != null) {
                quick.servedModel = call.servedModel();
            }
            if (call.cost() != null) {
                total = total == null ? call.cost() : total.plus(call.cost());
            }
        }
        if (total != null) {
            quick.cost = total.amount();
            quick.currency = total.currency();
        }
        return quick;
    }

    public String getAnswer() {return answer;}

    public void setAnswer(String answer) {this.answer = answer;}

    public String getReasoning() {return reasoning;}

    public void setReasoning(String reasoning) {this.reasoning = reasoning;}

    public String getModel() {return model;}

    public void setModel(String model) {this.model = model;}

    public String getServedModel() {return servedModel;}

    public void setServedModel(String servedModel) {this.servedModel = servedModel;}

    public Double getCost() {return cost;}

    public void setCost(Double cost) {this.cost = cost;}

    public String getCurrency() {return currency;}

    public void setCurrency(String currency) {this.currency = currency;}
}
