/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models.discovery;

import ai.redouble.nucleo.harness.models.*;

import java.io.*;
import java.util.*;

/**
 * What a provider knows about its own account that the catalog cannot: which models the
 * account can reach, and, where the provider publishes them through an API, the account's
 * limits and each model's retention posture. A {@code ClientProvider} that can answer
 * implements this beside its client construction; {@link CatalogDiscovery} walks every
 * configured provider on the classpath, asks the ones that implement it, and reports the ones
 * that do not as "listing unsupported" with their seed entries pinged blind.
 *
 * <p>The answer is the account's, not the world's: an Anthropic key lists the models that key
 * may call, an AWS identity lists what its region and account have access to. Limits that the
 * provider only reveals on a response (Anthropic's and OpenAI's rate-limit headers) are not
 * this method's business; the ping observes those.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-12)
 */
public interface ModelDiscovery {
    /**
     * Every model the account can reach on this provider's surface. The provider's own
     * failure (a rejected credential, an unreachable endpoint) propagates as-is; the discovery
     * reports it verbatim against the provider and moves on.
     */
    List<DiscoveredModel> listModels() throws IOException;
}
