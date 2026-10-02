/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

/**
 * The provider declined to answer: the call came back with an empty body and a stop reason
 * that says so (Anthropic's {@code refusal}, a guardrail, a content filter). Uncorrectable
 * on the model that refused: a safety classifier is a fact about the request as that model
 * reads it, and the runtime never re-rolls one. It is typed so that the caller can act on
 * the one thing that does change the outcome, the model: catch it, read
 * {@link #getCategory()}, and resubmit the same work on another binding. The exception
 * reaches a caller as the failure of the job that made the call, the cause of the
 * {@code ExecutionException} from {@code JobHandle.get()}.
 *
 * <p>{@link #getCategory()} is the provider's own word for the policy area
 * ({@code reasoning_extraction}, {@code cyber}, {@code bio}, ...), null when the provider
 * named none; {@link #getExplanation()} is its prose, null when it gave none. Neither is
 * stable text to parse: branch on the category, display the explanation.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public class ProviderRefusalException extends UncorrectableRuntimeLLMException {
    private final String modelId;
    private final String category;
    private final String explanation;

    public ProviderRefusalException(String modelId, String category, String explanation) {
        super(buildMessage(modelId, category, explanation));
        this.modelId = modelId;
        this.category = category;
        this.explanation = explanation;
    }

    private static String buildMessage(String modelId, String category, String explanation) {
        StringBuilder message = new StringBuilder(modelId).append(" refused the request");
        if (category != null) {
            message.append(" (").append(category).append(")");
        }
        message.append(": ").append(explanation != null ? explanation : "the provider filtered the content and gave no account");
        message.append(". Not retried on this model: a safety classifier is not re-rolled; resubmit on another model or change what is asked.");
        return message.toString();
    }

    /** The catalog id of the model that refused. */
    public String getModelId() {
        return modelId;
    }

    /** The provider's category for the refusal, null when it named none. */
    public String getCategory() {
        return category;
    }

    /** The provider's explanation, null when it gave none. */
    public String getExplanation() {
        return explanation;
    }
}
