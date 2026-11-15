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
 * declared refs and the refs written in the reply's values: variant spellings collapse to one
 * canonical entry, a written artifact survives a declared duplicate, order is
 * declared-then-first-mention, and {@code ArtifactRegistry.refsMentionedIn} is the one
 * authority for recognizing a ref in prose. {@code ArtifactRegistry.refsWrittenIn} finds the
 * written refs in a parsed reply: every string value at any depth, and no field name.
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

        answer.canonicalizeArtifactRefs(ArtifactRegistry.refsWrittenIn("as shown in «artifact:link:page~abc123», ..."));

        assertEquals(List.of("«artifact:link:page~abc123»"), answer.getArtifactRefs(),
                "the guillemet-less declared ref and its text mention are one artifact, kept once, canonically");
    }

    @Test
    void aTextMentionSurvivesADeclaredDuplicate() {
        // The old gate compared set size to declared size, so a model that duplicated a ref
        // in its own list masked a genuinely new text-mentioned artifact.
        Answer answer = new Answer();
        answer.setArtifactRefs(new ArrayList<>(List.of("«artifact:code~aaa111»", "«artifact:code~aaa111»")));

        answer.canonicalizeArtifactRefs(ArtifactRegistry.refsWrittenIn("see also «artifact:person~bbb222»"));

        assertEquals(List.of("«artifact:code~aaa111»", "«artifact:person~bbb222»"), answer.getArtifactRefs(),
                "the duplicate collapses and the text-mentioned artifact is kept");
    }

    @Test
    void orderIsDeclaredFirstThenFirstMention() {
        Answer answer = new Answer();
        answer.setArtifactRefs(new ArrayList<>(List.of("«artifact:code~x1»", "«artifact:code~x2»")));

        answer.canonicalizeArtifactRefs(ArtifactRegistry.refsWrittenIn("«artifact:code~x3» before «artifact:code~x2» and «artifact:code~x4»"));

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

        answer.canonicalizeArtifactRefs(List.of());
        assertEquals(List.of("«artifact:link:page~zz9»"), answer.getArtifactRefs());
    }

    @Test
    void aReferenceIsReadFromEveryStringValueOfAParsedReply_atAnyDepth() {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("summary", "starts with «artifact:code~top1»");
        reply.put("count", 3);
        reply.put("findings", List.of(
                Map.of("note", "nested «artifact:code~deep1»"),
                List.of("in a list of lists «artifact:person~deep2»")));
        reply.put("empty", null);

        assertEquals(List.of("«artifact:code~top1»", "«artifact:code~deep1»", "«artifact:person~deep2»"),
                ArtifactRegistry.refsWrittenIn(reply),
                "a top-level field, a field of a nested object and an element of a nested list are all read, in the order met");
    }

    @Test
    void aReferenceInAFieldNameIsNotRead() {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("«artifact:code~key1»", "a value with no reference");
        reply.put("labels", Map.of("«artifact:code~key2»", 7));

        assertEquals(List.of(), ArtifactRegistry.refsWrittenIn(reply),
                "a field's name is not a value: a reference that stands only there selects nothing");
    }

    @Test
    void aJsonTreeIsReadLikeTheMapItWouldParseTo() throws IOException {
        com.fasterxml.jackson.databind.JsonNode reply = NucleoJsonSerializer.readTree(
                "{\"«artifact:code~key1»\": \"plain\", \"text\": \"see «artifact:code~a1»\","
                        + " \"parts\": [{\"note\": \"and «artifact:code~a2», «artifact:code~a1» again\"}], \"n\": 2}");

        assertEquals(List.of("«artifact:code~a1»", "«artifact:code~a2»"), ArtifactRegistry.refsWrittenIn(reply),
                "values at any depth are read, a field name is not, and a reference written twice is found once");
    }

    @Test
    void aReferenceDeclaredAndAlsoWrittenIsSelectedOnce() {
        Answer answer = new Answer();
        answer.setArtifactRefs(new ArrayList<>(List.of("artifact:code~both1")));
        Map<String, Object> reply = Map.of("verdict", "per «artifact:code~both1»", "artifact_refs", List.of("artifact:code~both1"));

        answer.canonicalizeArtifactRefs(ArtifactRegistry.refsWrittenIn(reply));

        assertEquals(List.of("«artifact:code~both1»"), answer.getArtifactRefs(),
                "the declared spelling and the written one are one artifact, kept once");
    }
}
