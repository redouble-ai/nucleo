/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.retry;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;

/**
 * The provider account is out of money: no remaining credits, an exhausted
 * prepaid balance, or a spend budget set to zero. Distinct from a rate limit -
 * a rate limit clears on its own and is worth retrying, this never recovers by
 * retrying. Providers signal it differently (OpenAI: HTTP 429
 * {@code insufficient_quota}; Anthropic: HTTP 400 "credit balance is too low"),
 * so each client classifies it in its {@code isQuotaError} hook on {@link AbstractRateLimitedClient}
 * and the shared call paths raise this single type with a clear, human-facing
 * message naming the account.
 *
 * <p>Uncorrectable and runtime: it must propagate to the dispatcher as a hard
 * failure - it is deliberately NOT a {@link RateLimitRetryException}, so the
 * transparent rate-limit retry never picks it up and burns attempts on a
 * condition that cannot improve. The fix is operational (add funds), not
 * algorithmic.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-14)
 */
public class QuotaExhaustedException extends UncorrectableRuntimeLLMException {
    private final String provider;
    private final String account;
    private final String modelName;

    public QuotaExhaustedException(String provider, String account, String modelName, String providerDetail,
            Throwable cause) {
        super(buildMessage(provider, account, modelName, providerDetail), cause);
        this.provider = provider;
        this.account = account;
        this.modelName = modelName;
    }

    private static String buildMessage(String provider, String account, String modelName, String providerDetail) {
        StringBuilder s = new StringBuilder();
        s.append("OUT OF MONEY: the ").append(provider).append(" account [").append(account)
         .append("] has no remaining credits or quota");
        if (modelName != null) {
            s.append(" (model ").append(modelName).append(")");
        }
        s.append(". Calls will keep failing until the account is funded or its spend budget is raised - "
                + "this does not recover by retrying.");
        if (providerDetail != null && !providerDetail.isBlank()) {
            s.append(" Provider said: ").append(providerDetail.trim());
        }
        return s.toString();
    }

    public String getProvider() {
        return provider;
    }

    public String getAccount() {
        return account;
    }

    public String getModelName() {
        return modelName;
    }
}
