/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import java.util.*;

/**
 * How Claude models are named in the catalog, from any of the spellings the surfaces use:
 * the identity is the family and the version with a dot ({@code opus-4.7}, {@code fable-5.1},
 * {@code haiku-3}), read from {@code claude-opus-4-7}, {@code anthropic.claude-opus-4-7},
 * {@code claude-opus-4-5-20251101-v1:0} or the old {@code claude-3-haiku-20240307}; the
 * catalog id is {@code claude-} plus the identity with dashes plus the channel the entry is
 * served on ({@code claude-opus-4-7-bedrock}). Shared by every provider that serves Claude:
 * the direct API, Bedrock's native surface, Mantle, and Converse for its Claude entries.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public final class AnthropicNaming {
    private AnthropicNaming() {}

    /** Whether a wire id names a Claude model, on any surface. */
    public static boolean isClaude(String wireModelId) {
        String bare = ModelLineage.bare(wireModelId);
        return bare.startsWith("claude-") || bare.startsWith("anthropic.claude-");
    }

    public static String identityOf(String wireModelId) {
        String id = ModelLineage.identityOf(wireModelId);
        if (id.startsWith("claude-")) {
            id = id.substring("claude-".length());
        }
        List<String> family = new ArrayList<>();
        List<String> version = new ArrayList<>();
        for (String token : id.split("-")) {
            if (token.matches("\\d+")) {
                version.add(token);
            }
            else {
                family.add(token);
            }
        }
        return String.join("-", family) + (version.isEmpty() ? "" : "-" + String.join(".", version));
    }

    public static String catalogId(String identity, String channel) {
        return "claude-" + identity.replace('.', '-') + "-" + channel;
    }
}
