/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models.discovery;

import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * One availability observation of one catalog entry, outside the dispatcher: a client built
 * straight from the entry's provider, one minimal call, and a {@link ProbeOutcome} whatever
 * happens. This is the probe recipe the platform's scheduled sweep uses, without the job
 * around it, because the catalog discovery runs before a catalog, a picker or a dispatcher
 * exists.
 *
 * <p>The call: a one-word prompt at {@code Depth.IMMEDIATE} with 2048 tokens of output
 * headroom, because reasoning-effort models spend output budget on reasoning before any text
 * and a tiny ceiling truncates every one of them into a false failure; the answer stops at the
 * first real token, so the headroom is never spent. An embeddings entry embeds one word. Two
 * minutes is the cap: a one-token call that cannot answer in two minutes IS the answer.
 *
 * <p>A failed call is a RESULT. Upstream signals and the LLM-readable family become
 * {@code FAILED} outcomes with a classification, because the question is "does this succeed
 * right now", and a transparent retry would answer a different question later. The rate-limit
 * headers a provider sends on the response ride the outcome verbatim, which is how the
 * discovery learns an account's real limits where the provider has no quota API.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-12)
 */
public final class CatalogPing {
    static final String PING_PROMPT = "Reply with the single word: pong";
    static final int PING_OUTPUT_BUDGET = 2048;
    static final Duration PING_CAP = Duration.ofMinutes(2);

    private CatalogPing() {}

    /** Pings the entry through its provider and returns what happened, never throwing. */
    public static ProbeOutcome ping(ClientProvider<?> provider, ModelSpec spec) {
        ProbeOutcome outcome = new ProbeOutcome();
        outcome.setSpecId(spec.getId());
        outcome.setProvider(spec.getProviderKey());
        outcome.setProbedAt(Instant.now());
        long start = System.currentTimeMillis();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> call = executor.submit(() -> {
                call(provider, spec, outcome);
                return null;
            });
            try {
                call.get(PING_CAP.toMillis(), TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException e) {
                call.cancel(true);
                outcome.setStatus(ProbeOutcome.Status.FAILED);
                outcome.setClassification(ProbeOutcome.Classification.AVAILABILITY);
                outcome.setErrorClass(e.getClass().getSimpleName());
                outcome.setErrorMessage("no answer within " + PING_CAP.toSeconds() + "s");
            }
            catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                outcome.setStatus(ProbeOutcome.Status.FAILED);
                outcome.setClassification(cause instanceof Exception ex ? classify(ex) : ProbeOutcome.Classification.OTHER);
                outcome.setErrorClass(cause.getClass().getSimpleName());
                outcome.setErrorMessage(describe(cause));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                outcome.setStatus(ProbeOutcome.Status.FAILED);
                outcome.setClassification(ProbeOutcome.Classification.OTHER);
                outcome.setErrorClass(e.getClass().getSimpleName());
                outcome.setErrorMessage("interrupted");
            }
        }
        outcome.setLatencyMs(System.currentTimeMillis() - start);
        return outcome;
    }

    /**
     * A failure's whole account, outermost first: the framework's message, then each cause's
     * own words where they add something. A client composes its LLM-facing message from what
     * the process knows (the status, the model) and leaves the provider's body in the cause; a
     * discovery report is read by a person deciding what to do about the entry, and the body
     * is the part that tells them.
     */
    static String describe(Throwable failure) {
        StringBuilder sb = new StringBuilder();
        String last = null;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null || message.isBlank() || message.equals(last) || (last != null && last.contains(message))) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(" <- ").append(current.getClass().getSimpleName()).append(": ");
            }
            sb.append(message);
            last = message;
        }
        return sb.isEmpty() ? failure.getClass().getSimpleName() : sb.toString();
    }

    private static void call(ClientProvider<?> provider, ModelSpec spec, ProbeOutcome outcome) throws Exception {
        Client client = provider.createClient(spec);
        try {
            if (spec.isEmbeddings()) {
                if (!(client instanceof EmbeddingsClient embeddings)) {
                    throw new UncorrectableRuntimeLLMException("Provider '" + spec.getProviderKey()
                            + "' built " + client.getClass().getSimpleName() + " for embeddings entry " + spec.getId());
                }
                embeddings.calculateEmbedding("ping", EmbeddingPurpose.QUERY);
                outcome.setStatus(ProbeOutcome.Status.OK);
                return;
            }
            if (spec.isDecision()) {
                if (!(client instanceof DecisionClient decision)) {
                    throw new UncorrectableRuntimeLLMException("Provider '" + spec.getProviderKey()
                            + "' built " + client.getClass().getSimpleName() + " for decision entry " + spec.getId());
                }
                LinkedHashMap<String, Question> questions = new LinkedHashMap<>();
                questions.put("ping", new Noul("Is this a ping?", null, null));
                DecisionResponse response = decision.decide(new DecisionRequest("ping", questions));
                outcome.setStatus(response.isSuccessful() ? ProbeOutcome.Status.OK : ProbeOutcome.Status.FAILED);
                outcome.setServedModelId(response.getServedModelId());
                outcome.setProviderRequestId(response.getProviderRequestId());
                if (response.getActualInputTokens() != null) {
                    outcome.setInputTokens(Long.valueOf(response.getActualInputTokens()));
                }
                return;
            }
            if (!(client instanceof LLMClient llm)) {
                throw new UncorrectableRuntimeLLMException("Provider '" + spec.getProviderKey()
                        + "' built " + client.getClass().getSimpleName() + " for LLM entry " + spec.getId());
            }
            harvest(outcome, llm.singleResponse(new LLMRequest<>(conversation(llm, spec))));
        }
        finally {
            if (client instanceof LLMClient llm) {
                llm.close();
            }
        }
    }

    private static ConversationContext conversation(LLMClient client, ModelSpec spec) {
        ConversationContext conversation = new ConversationContext();
        conversation.setModelBinding(ModelBinding.preResolved(spec));
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(PING_OUTPUT_BUDGET));
        OutgoingMessage<String> message = client.createOutgoingMessage(StringResponseHandler.instance);
        message.setRole("user");
        message.setTimestamp(Instant.now());
        message.addText(PING_PROMPT);
        conversation.getMessages().add(message);
        return conversation;
    }

    /**
     * A ping's response onto its outcome: success or a failure classified OTHER, and every fact
     * the response carries, the served model, the request id, the headers verbatim, the usage,
     * the stop reason and the rate limits the provider reported. The platform's scheduled probe
     * reads its responses the same way.
     */
    public static void harvest(ProbeOutcome outcome, LLMResponse<String> response) {
        if (response.isSuccessful()) {
            outcome.setStatus(ProbeOutcome.Status.OK);
        }
        else {
            outcome.setStatus(ProbeOutcome.Status.FAILED);
            outcome.setClassification(ProbeOutcome.Classification.OTHER);
            outcome.setErrorClass(response.getLastError() != null ? response.getLastError().getClass().getSimpleName() : null);
            outcome.setErrorMessage(response.getReasonForFailure());
        }
        outcome.setServedModelId(response.getServedModelId());
        outcome.setProviderRequestId(response.getProviderRequestId());
        outcome.setHeaders(response.getProviderHeaders());
        if (response.getActualInputTokens() != null) {
            outcome.setInputTokens(response.getActualInputTokens().longValue());
        }
        if (response.getActualOutputTokens() != null) {
            outcome.setOutputTokens(response.getActualOutputTokens().longValue());
        }
        if (response.getStopReason() != null) {
            outcome.setStopReason(response.getStopReason().name());
        }
        RateLimitInfo limits = response.getRateLimitInfo();
        if (limits != null) {
            if (limits.getTokensLimit() != null) {
                outcome.setObservedTokensLimit(limits.getTokensLimit().longValue());
            }
            if (limits.getRequestsLimit() != null) {
                outcome.setObservedRequestsLimit(limits.getRequestsLimit().longValue());
            }
        }
    }

    /**
     * Availability-shaped failures mean "down"; a 429 means "busy" and a rejected credential
     * means "the configuration is wrong" - both are reported as such, never as the model
     * being unavailable.
     */
    public static ProbeOutcome.Classification classify(Exception e) {
        return switch (e) {
            case RateLimitRetryException rateLimited -> ProbeOutcome.Classification.THROTTLE;
            case OverloadRetryException overloaded -> ProbeOutcome.Classification.AVAILABILITY;
            case TransientErrorRetryException transientError -> ProbeOutcome.Classification.AVAILABILITY;
            case UnauthorizedException unauthorized -> ProbeOutcome.Classification.AUTH;
            case ExternalServiceException external -> ProbeOutcome.Classification.AVAILABILITY;
            case ResourceNotFoundException notFound -> ProbeOutcome.Classification.AVAILABILITY;
            default -> ProbeOutcome.Classification.OTHER;
        };
    }
}
