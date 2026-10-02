/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;



/**
 * Deterministic text form of a {@link ListArtifact}: a header with the count and
 * iterand type, then every iterand numbered in list order, each rendered through
 * its own formatter via {@link TextFormatterRegistry}. Nested lists recurse
 * naturally. Same iterands in the same order always produce the same text.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-06-12)
 */
public class ListArtifactTextFormatter implements ArtifactTextFormatter<ListArtifact<?>> {

    @Override
    public String format(ListArtifact<?> list) {
        StringBuilder sb = new StringBuilder();
        int count = list.getIterands() == null ? 0 : list.getIterands().size();
        sb.append(count);
        if (list.getIterandTypeAlias() != null) {
            sb.append(' ').append(list.getIterandTypeAlias());
        }
        sb.append(count == 1 ? " result" : " results");
        sb.append('\n');
        for (int i = 0; i < count; i++) {
            sb.append('\n').append(i + 1).append(". ");
            sb.append(TextFormatterRegistry.format(list.getIterands().get(i)));
            sb.append('\n');
        }
        return sb.toString();
    }
}
