/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.decision;

import ai.redouble.nucleo.harness.schema.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The System One wire, without a transport: a request encodes to the body every endpoint
 * of the class reads, and a body decodes strictly against what was asked - every question
 * answered in its own shape with the option keys it named, unknown fields ignored, and a
 * refusal that names what was missing and quotes nothing of the state.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
class SystemOneWireTest {
    static final String STATE_CANARY = "STATE-CANARY-4c1e";

    private static DecisionRequest request() {
        LinkedHashMap<String, Question> questions = new LinkedHashMap<>();
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        options.put("billing", "Payments, invoicing, refunds");
        options.put("technical", null);
        questions.put("department", new Choice("Which team should handle this?", options));
        questions.put("is_urgent", new Noul("Does this convey urgency?", "Explicitly time-sensitive", "No urgency expressed"));
        questions.put("frustration", Score.of("How frustrated is the customer?", "Calm", "Frustrated", "Very angry"));
        return new DecisionRequest("Help! My payouts have been failing for 3 days. " + STATE_CANARY, questions);
    }

    static final String ANSWER = "{\"model\":\"jev-1.13.0\",\"latency_ms\":203.1,"
            + "\"answers\":{"
            + "\"department\":{\"type\":\"choice\",\"choice\":\"billing\",\"confidence\":0.8,\"probabilities\":{\"billing\":0.87,\"technical\":0.13},\"extra\":1},"
            + "\"is_urgent\":{\"type\":\"noul\",\"noul\":0.95},"
            + "\"frustration\":{\"type\":\"score\",\"score\":1.04,\"confidence\":0.94,\"legend\":{\"0\":\"Calm\",\"1\":\"Frustrated\",\"2\":\"Very angry\"},\"probabilities\":{\"0\":0,\"1\":0.96,\"2\":0.04}},"
            + "\"unasked\":{\"type\":\"noul\",\"noul\":0.5}},"
            + "\"usage\":{\"input_tokens\":426,\"output_tokens\":73}}";

    @Test
    void aRequestEncodesToTheBodyEveryEndpointOfTheClassReads() throws IOException {
        JsonNode body = NucleoJsonSerializer.readTree(SystemOneWire.encode("kev-latest", request()));
        assertEquals("kev-latest", body.get("model").asText());
        assertTrue(body.get("state").isTextual(), "a text state rides as text");
        JsonNode department = body.get("questions").get("department");
        assertEquals("choice", department.get("type").asText());
        assertEquals("Which team should handle this?", department.get("instructions").asText());
        assertEquals("Payments, invoicing, refunds", department.get("criteria").get("billing").asText());
        assertTrue(department.get("criteria").get("technical").isNull(), "an option without a description is a null criterion");
        assertEquals(List.of("billing", "technical"), names(department.get("criteria")), "options keep the order asked");
        JsonNode urgent = body.get("questions").get("is_urgent");
        assertEquals("noul", urgent.get("type").asText());
        assertEquals("Explicitly time-sensitive", urgent.get("criteria").get("true").asText());
        assertEquals("No urgency expressed", urgent.get("criteria").get("false").asText());
        JsonNode frustration = body.get("questions").get("frustration");
        assertEquals("score", frustration.get("type").asText());
        assertEquals(3, frustration.get("criteria").size());
        assertEquals("Very angry", frustration.get("criteria").get(2).asText());
    }

    @Test
    void aNoulWithoutDescriptions_andAStructuredState_encodeAsTheWireSpells() throws IOException {
        DecisionRequest request = new DecisionRequest(Map.of("document_id", "INV-2087", "text", "Total due: $1,315.50"),
                Map.of("has_total", Noul.of("Does the invoice state a total?")));
        JsonNode body = NucleoJsonSerializer.readTree(SystemOneWire.encode(null, request));
        assertFalse(body.has("model"), "a null model id leaves the field out: the body as counted before resolution");
        assertEquals("INV-2087", body.get("state").get("document_id").asText(), "a structured state rides as JSON");
        assertFalse(body.get("questions").get("has_total").has("criteria"), "a noul with no descriptions sends no criteria");
    }

    @Test
    void aBodyDecodesToTheAnswersInTheShapeAsked_ignoringWhatItDidNotAskFor() throws IOException {
        SystemOneWire.Decoded decoded = SystemOneWire.decode(ANSWER, request());
        assertEquals(List.of("department", "is_urgent", "frustration"), new ArrayList<>(decoded.answers().keySet()),
                "the answers come in the order asked and the unasked one is ignored");
        ChoiceAnswer department = (ChoiceAnswer) decoded.answers().get("department");
        assertEquals("billing", department.choice());
        assertEquals(0.87, department.probability(), 1e-9);
        assertEquals(0.13, department.probabilities().get("technical"), 1e-9);
        assertEquals(0.8, department.confidence(), 1e-9);
        assertEquals(0.95, ((NoulAnswer) decoded.answers().get("is_urgent")).probability(), 1e-9);
        ScoreAnswer frustration = (ScoreAnswer) decoded.answers().get("frustration");
        assertEquals(1.04, frustration.score(), 1e-9);
        assertEquals(1, frustration.topLevel());
        assertEquals(List.of("Calm", "Frustrated", "Very angry"), frustration.levels());
        assertEquals(0.96, frustration.probabilities().get(1), 1e-9);
        assertEquals(0.94, frustration.confidence(), 1e-9);
        assertEquals(426, decoded.inputTokens());
        assertEquals("jev-1.13.0", decoded.servedModelId());
    }

    @Test
    void aBodyWithoutUsageOrModelStillDecodes_withThoseFactsNull() throws IOException {
        String bare = "{\"answers\":{\"ok\":{\"type\":\"noul\",\"noul\":0.5}}}";
        SystemOneWire.Decoded decoded = SystemOneWire.decode(bare, new DecisionRequest("x", Map.of("ok", Noul.of("Ok?"))));
        assertNull(decoded.inputTokens());
        assertNull(decoded.servedModelId());
    }

    @Test
    void whatIsMissingIsRefusedByName_andTheStateIsNeverQuoted() {
        DecisionRequest request = request();
        assertRefused(request, "{\"answers\":{}}", "department");
        assertRefused(request, "not json at all", "JSON");
        assertRefused(request, "{\"nothing\":1}", "answers");
        assertRefused(request, ANSWER.replace("\"choice\":\"billing\"", "\"choice\":\"sales\""), "not among the options");
        assertRefused(request, ANSWER.replace("\"technical\":0.13", "\"other\":0.13"), "technical");
        assertRefused(request, ANSWER.replace("\"noul\":0.95", "\"noul\":\"yes\""), "noul");
        assertRefused(request, ANSWER.replace("\"2\":0.04", "\"9\":0.04"), "level 2");
        assertRefused(request, ANSWER.replace("\"confidence\":0.8,", ""), "confidence");
        assertRefused(request, ANSWER.replace("\"score\":1.04,\"confidence\":0.94,", "\"score\":1.04,"), "confidence");
        assertRefused(request, ANSWER.replace("\"choice\":\"billing\",", ""), "choice");
        assertRefused(request, ANSWER.replace("\"score\":1.04,", ""), "score");
        assertRefused(request, ANSWER.replace("\"noul\":0.95", "\"noul\":1.5"), "between 0 and 1");
    }

    private static void assertRefused(DecisionRequest request, String body, String named) {
        IOException refusal = assertThrows(IOException.class, () -> SystemOneWire.decode(body, request), body);
        String chain = chain(refusal);
        assertTrue(chain.contains(named), "the refusal names what was wrong (" + named + "): " + chain);
        assertFalse(chain.contains(STATE_CANARY), "the state never rides in a refusal: " + chain);
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    private static List<String> names(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void theVocabularyRefusesWhatCannotBeAsked_orAnswered() {
        assertThrows(IllegalArgumentException.class, () -> new Choice("Which?", Map.of()), "a choice needs options");
        assertThrows(IllegalArgumentException.class, () -> new Choice("Which?", Map.of(" ", "A")), "every option has a key");
        assertThrows(IllegalArgumentException.class, () -> new Choice(" ", Map.of("a", "A")), "a question needs instructions");
        assertThrows(IllegalArgumentException.class, () -> new Noul("Is it?", "yes means", null), "a noul describes both or neither");
        assertThrows(IllegalArgumentException.class, () -> Score.of("How much?", "only one"), "a score needs two levels");
        assertThrows(IllegalArgumentException.class, () -> Score.of("How much?", "low", " "), "every level is described");
        assertThrows(IllegalArgumentException.class, () -> new DecisionRequest("state", Map.of()), "a request asks something");
        assertThrows(IllegalArgumentException.class, () -> new DecisionRequest("state", Map.of(" ", Noul.of("Q?"))), "every question has an id");
        assertThrows(IllegalArgumentException.class, () -> new DecisionRequest(null, Map.of("q", Noul.of("Q?"))), "a request has a state");
        assertThrows(IllegalArgumentException.class, () -> new NoulAnswer(1.2));
        assertThrows(IllegalArgumentException.class, () -> new ChoiceAnswer("c", Map.of("a", 1.0), 0.5), "the winner is among the options");
        Map<String, Double> unanswered = new LinkedHashMap<>();
        unanswered.put("a", 0.6);
        unanswered.put("b", null);
        assertThrows(IllegalArgumentException.class, () -> new ChoiceAnswer("a", unanswered, 0.5), "every option has a probability");
        assertThrows(IllegalArgumentException.class, () -> new ChoiceAnswer("a", Map.of("a", 1.5), 0.5), "a probability between 0 and 1");
        assertThrows(IllegalArgumentException.class, () -> new ChoiceAnswer("a", Map.of("a", 1.0), 1.5), "a confidence between 0 and 1");
        assertThrows(IllegalArgumentException.class, () -> new ScoreAnswer(0.5, List.of("a", "b"), List.of(0.5, 0.5), 1.5), "a score's confidence too");
        assertThrows(IllegalArgumentException.class, () -> new ScoreAnswer(0.5, List.of("a", "b"), List.of(1.0), 0.5), "one probability per level");
        assertThrows(IllegalArgumentException.class, () -> new ScoreAnswer(3.0, List.of("a", "b"), List.of(0.5, 0.5), 0.5), "a score sits on the scale");
    }
}
