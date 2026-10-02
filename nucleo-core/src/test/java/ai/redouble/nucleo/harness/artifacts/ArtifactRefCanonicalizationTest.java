/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts;

import ai.redouble.nucleo.harness.schema.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the single canonicalization gate for a parsed response's artifact refs. The model's
 * declared list enters through the package-private Jackson setter (its one door from outside
 * the package), and {@code canonicalizeArtifactRefs} replaces it with the normalized union of
 * declared refs and text mentions: variant spellings collapse to one canonical entry, a
 * text-mentioned artifact survives a declared duplicate, order is declared-then-first-mention,
 * and {@code ArtifactRegistry.refsMentionedIn} is the one authority for recognizing a ref in
 * prose.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
public class ArtifactRefCanonicalizationTest {

    /** A concrete response shape, as an application would declare one. */
    public static class Answer extends ArtifactResponse<SimpleReasoning> {
        private String verdict;

        public String getVerdict() {
            return verdict;
        }

        public void setVerdict(String verdict) {
            this.verdict = verdict;
        }
    }

    @Test
    void declaredVariantAndTextMentionCollapseToOneCanonicalEntry() {
        Answer answer = new Answer();
        answer.setArtifactRefs(new ArrayList<>(List.of("artifact:link:page~abc123")));

        answer.canonicalizeArtifactRefs("as shown in «artifact:link:page~abc123», ...");

        assertEquals(List.of("«artifact:link:page~abc123»"), answer.getArtifactRefs(),
                "the guillemet-less declared ref and its text mention are one artifact, kept once, canonically");
    }

    @Test
    void aTextMentionSurvivesADeclaredDuplicate() {
        // The old gate compared set size to declared size, so a model that duplicated a ref
        // in its own list masked a genuinely new text-mentioned artifact.
        Answer answer = new Answer();
        answer.setArtifactRefs(new ArrayList<>(List.of("«artifact:code~aaa111»", "«artifact:code~aaa111»")));

        answer.canonicalizeArtifactRefs("see also «artifact:person~bbb222»");

        assertEquals(List.of("«artifact:code~aaa111»", "«artifact:person~bbb222»"), answer.getArtifactRefs(),
                "the duplicate collapses and the text-mentioned artifact is kept");
    }

    @Test
    void orderIsDeclaredFirstThenFirstMention() {
        Answer answer = new Answer();
        answer.setArtifactRefs(new ArrayList<>(List.of("«artifact:code~x1»", "«artifact:code~x2»")));

        answer.canonicalizeArtifactRefs("«artifact:code~x3» before «artifact:code~x2» and «artifact:code~x4»");

        assertEquals(List.of("«artifact:code~x1»", "«artifact:code~x2»", "«artifact:code~x3»", "«artifact:code~x4»"),
                answer.getArtifactRefs(),
                "declared refs keep their order; new mentions append in first-mention order");
    }

    @Test
    void refsMentionedInIsTheOneProseAuthority() {
        List<String> refs = ArtifactRegistry.refsMentionedIn(
                "«artifact:code~x1» twice: «artifact:code~x1», then «artifact:person~p1»");

        assertEquals(List.of("«artifact:code~x1»", "«artifact:person~p1»"), refs,
                "mentions come back normalized, deduplicated, in first-mention order");
        assertEquals(List.of(), ArtifactRegistry.refsMentionedIn("no refs here"),
                "a text with no mentions is an empty answer, not an error");
    }

    @Test
    void jacksonWritesTheModelsListThroughThePackagePrivateSetter() throws IOException {
        // The setter is package-private so no outside caller can plant a weird ref, but it
        // stays Jackson's door: the model's parsed answer must still fill the field.
        Answer answer = NucleoJsonSerializer.parse(
                "{\"verdict\": \"ok\", \"artifact_refs\": [\"artifact:link:page~zz9\"]}", Answer.class);

        assertEquals(List.of("artifact:link:page~zz9"), answer.getArtifactRefs(),
                "the parsed list arrives exactly as the model wrote it - the gate canonicalizes right after");

        answer.canonicalizeArtifactRefs(null);
        assertEquals(List.of("«artifact:link:page~zz9»"), answer.getArtifactRefs());
    }
}
