/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.registry.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The answer's shape against the schema the handler published, judged as a boundary: a model
 * is a third party, its output is untrusted, and this is where that output becomes a typed
 * object.
 *
 * <p>Three laws. <b>An answer the declared type cannot hold is refused</b>: converting a JSON
 * object into a composite answer type is the only conversion there is, so a composite type
 * refuses anything that is not an object, and a leaf type refuses everything it is not already
 * an instance of - an object, an array, and a scalar of another kind alike. The mirror holds:
 * the shape the schema asked for is accepted, whatever else the envelope carries.
 * <b>Nothing the model sent comes back through a refusal</b>: every payload carries a canary,
 * the shape refusal names the parameter, the rule and the offending Java type, and the
 * refusal for a mistyped field inside a right-shaped answer is composed from the declared
 * type the decoder carries - an enum's accepted values, another type's name - never from the
 * decoder's complaint about the value. The complaint survives on the retained cause, which a
 * stack trace may carry and prose never does. <b>The refusal is correctable</b>, because the
 * only useful reaction is
 * to re-ask; letting a wrong shape through instead reaches the thinking loop's erased cast,
 * where it becomes an uncorrectable system error and the run is lost with its completed tool
 * results.
 *
 * <p>What this class does NOT pin is the leniency of the decoder underneath
 * ({@code NucleoJsonSerializer}, which accepts comments, trailing commas and unknown
 * properties by design because its input is our own model's output). Those payloads are here
 * to record what that decoder does, not to demand it change.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public class ThinkingResponseHandlerAnswerShapeTest {
    /** In every payload's value position, so a refusal that quotes the model is caught by construction. */
    private static final String CANARY = "zqx7value";
    /** In every payload's key position where the hostile element is a name. */
    private static final String KEY_CANARY = "zqx7key";

    /** A composite answer type: the schema asks the model for an object. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ThinkingResponseHandler<Answer> compositeHandler() {
        return new ThinkingResponseHandler<>(new ToolRegistry(),
                new PojoResponseHandler<ThinkingResponse<Answer>>((Class)ThinkingResponse.class),
                new PojoResponseHandler<>(Answer.class));
    }

    /** A leaf answer type, the shape {@code ReactiveThinker} declares: the schema asks for a string. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ThinkingResponseHandler<String> leafHandler() {
        return new ThinkingResponseHandler<>(new ToolRegistry(),
                new PojoResponseHandler<ThinkingResponse<String>>((Class)ThinkingResponse.class),
                StringResponseHandler.instance);
    }

    private record Shape(String name, String envelope) {
    }

    /** Answers a composite schema must refuse: everything that is not a JSON object. */
    private static List<Shape> refusedByCompositeSchema() {
        return List.of(
                new Shape("a flattened string answer", "{\"final_answer\": true, \"answer\": \"" + CANARY + "\"}"),
                new Shape("an integer answer", "{\"final_answer\": true, \"answer\": 7}"),
                new Shape("a floating point answer", "{\"final_answer\": true, \"answer\": 7.5}"),
                new Shape("a boolean answer", "{\"final_answer\": true, \"answer\": true}"),
                new Shape("an array answer", "{\"final_answer\": true, \"answer\": [\"" + CANARY + "\"]}"),
                new Shape("an array of objects where one object was asked for",
                        "{\"final_answer\": true, \"answer\": [{\"value\": \"" + CANARY + "\"}]}"),
                new Shape("a numeric string that would once have coerced", "{\"final_answer\": true, \"answer\": \"7\"}"),
                new Shape("a JSON-looking string that was never parsed",
                        "{\"final_answer\": true, \"answer\": \"{\\\"value\\\": \\\"" + CANARY + "\\\"}\"}"),
                new Shape("an empty string answer", "{\"final_answer\": true, \"answer\": \"\"}"),
                new Shape("an empty array answer", "{\"final_answer\": true, \"answer\": []}"),
                new Shape("a huge scalar answer", "{\"final_answer\": true, \"answer\": \"" + CANARY + "x".repeat(20000) + "\"}"));
    }

    /**
     * Answers a leaf schema must refuse: every shape a {@code String} cannot hold, structural
     * and scalar alike. The scalar kinds matter as much as the objects: the one production seat
     * that declares a leaf answer type, {@code ReactiveThinker}, assigns the answer straight
     * into a {@code String} slot, so an integer or a boolean lands in a cast there.
     */
    private static List<Shape> refusedByLeafSchema() {
        return List.of(
                new Shape("an object answer where a string was asked for",
                        "{\"final_answer\": true, \"answer\": {\"" + KEY_CANARY + "\": \"" + CANARY + "\"}}"),
                new Shape("an empty object answer where a string was asked for", "{\"final_answer\": true, \"answer\": {}}"),
                new Shape("an array answer where a string was asked for",
                        "{\"final_answer\": true, \"answer\": [\"" + CANARY + "\"]}"),
                new Shape("an empty array answer where a string was asked for", "{\"final_answer\": true, \"answer\": []}"),
                new Shape("a nested object answer where a string was asked for",
                        "{\"final_answer\": true, \"answer\": {\"a\": {\"b\": [\"" + CANARY + "\"]}}}"),
                new Shape("a huge array answer where a string was asked for",
                        "{\"final_answer\": true, \"answer\": [\"" + CANARY + "x".repeat(20000) + "\"]}"),
                new Shape("an integer answer where a string was asked for", "{\"final_answer\": true, \"answer\": 7}"),
                new Shape("a floating point answer where a string was asked for", "{\"final_answer\": true, \"answer\": 7.5}"),
                new Shape("a boolean answer where a string was asked for", "{\"final_answer\": true, \"answer\": true}"));
    }

    /**
     * Answers whose shape is right but whose declared FIELDS are mistyped, so the conversion
     * fails inside the decoder rather than at the shape check. These reach the refusal in
     * {@code convertAnswer} that judges a failed conversion, whose text is composed from the
     * declared type the decoder carries, never from the decoder's complaint about the value.
     */
    private static List<Shape> mistypedFieldsInsideTheRightShape() {
        return List.of(
                new Shape("a string where the answer's integer field was declared",
                        "{\"final_answer\": true, \"answer\": {\"value\": \"v\", \"count\": \"" + CANARY + "\"}}"),
                new Shape("an object where the answer's enum field was declared",
                        "{\"final_answer\": true, \"answer\": {\"value\": \"v\", \"verdict\": {\"" + KEY_CANARY + "\": \"" + CANARY + "\"}}}"),
                new Shape("an unknown constant where the answer's enum field was declared",
                        "{\"final_answer\": true, \"answer\": {\"value\": \"v\", \"verdict\": \"" + CANARY + "\"}}"));
    }

    @TestFactory
    public Collection<DynamicTest> compositeSchemaRefusesEveryOtherShape() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Shape shape : refusedByCompositeSchema()) {
            tests.add(DynamicTest.dynamicTest(shape.name(), () -> {
                LLMReadableCheckedException refused = assertThrows(LLMReadableCheckedException.class,
                        () -> compositeHandler().parse(shape.envelope()),
                        "an answer that is not the object the schema asked for must be refused, not passed on");
                assertTrue(refused.isCorrectable(), "the model can fix the shape and be asked again: " + shape.name());
            }));
        }
        return tests;
    }

    @TestFactory
    public Collection<DynamicTest> leafSchemaRefusesEveryStructuralShape() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Shape shape : refusedByLeafSchema()) {
            tests.add(DynamicTest.dynamicTest(shape.name(), () -> {
                LLMReadableCheckedException refused = assertThrows(LLMReadableCheckedException.class,
                        () -> leafHandler().parse(shape.envelope()),
                        "an object or array where the schema asked for a scalar must be refused, not passed on");
                assertTrue(refused.isCorrectable(), "the model can fix the shape and be asked again: " + shape.name());
            }));
        }
        return tests;
    }

    /** Law 2, by construction over every corpus: a refusal is composed from the contract, never from the payload. */
    @TestFactory
    public Collection<DynamicTest> noRefusalQuotesWhatTheModelSent() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Shape shape : refusedByCompositeSchema()) {
            tests.add(DynamicTest.dynamicTest("composite: " + shape.name(), () -> assertNoCanary(shape, () -> compositeHandler().parse(shape.envelope()))));
        }
        for (Shape shape : refusedByLeafSchema()) {
            tests.add(DynamicTest.dynamicTest("leaf: " + shape.name(), () -> assertNoCanary(shape, () -> leafHandler().parse(shape.envelope()))));
        }
        for (Shape shape : mistypedFieldsInsideTheRightShape()) {
            tests.add(DynamicTest.dynamicTest("mistyped field: " + shape.name(), () -> assertNoCanary(shape, () -> compositeHandler().parse(shape.envelope()))));
        }
        return tests;
    }

    /**
     * The refusal's own text, at every level that leaves this process as prose: the message the
     * exception renders, and the message it renders for the model, which is what the loop
     * appends as a correction turn, what is logged as a warning, and what is persisted on the
     * conversation. A retained cause is deliberately NOT asserted on: the framework requires
     * that a wrapped exception keep its cause, and a decoder's cause carries the decoder's own
     * complaint about the value it refused. That text reaches a stack trace only, which has the
     * same compliance profile as the rest of the application's logs, and dropping the cause to
     * silence it would break the rule that a cause is never lost.
     */
    private void assertNoCanary(Shape shape, Executable call) {
        Throwable thrown = assertThrows(Throwable.class, call, shape.name());
        assertFalse(String.valueOf(thrown.getMessage()).contains(CANARY),
                "the refusal must not quote the model's value: " + shape.name() + " -> " + thrown.getMessage());
        assertFalse(String.valueOf(thrown.getMessage()).contains(KEY_CANARY),
                "the refusal must not quote the model's key: " + shape.name() + " -> " + thrown.getMessage());
        for (Throwable link = thrown; link != null; link = link.getCause()) {
            if (link instanceof LLMReadable readable) {
                assertFalse(readable.getLLMMessage().contains(CANARY),
                        "the text sent back to the model must not quote its value: " + shape.name() + " -> " + readable.getLLMMessage());
                assertFalse(readable.getLLMMessage().contains(KEY_CANARY),
                        "the text sent back to the model must not quote its key: " + shape.name() + " -> " + readable.getLLMMessage());
            }
        }
    }

    /** Law 3: the refusal names the parameter and what is accepted, so a corrected answer is possible. */
    @Test
    public void refusalNamesTheParameterAndTheAcceptedShape() {
        InvalidInputException refused = assertThrows(InvalidInputException.class,
                () -> compositeHandler().parse("{\"final_answer\": true, \"answer\": \"" + CANARY + "\"}"));
        assertEquals("answer", refused.getParameterName(), "the refusal names the field the schema declares");
        assertTrue(refused.getValidationRule().contains("JSON object"),
                "the rule states what is accepted: " + refused.getValidationRule());
    }

    // ---- positive controls: the shape the schema asked for is accepted ----

    @Test
    public void compositeSchemaAcceptsTheObjectItAskedFor() throws Exception {
        ThinkingResponse<Answer> response = compositeHandler().parse("{\"final_answer\": true, \"answer\": {\"value\": \"" + CANARY + "\"}}");
        assertInstanceOf(Answer.class, response.getAnswer(), "an object answer arrives as the declared type");
        assertEquals(CANARY, ((Answer)response.getAnswer()).getValue(), "the value survives the conversion");
    }

    @Test
    public void leafSchemaAcceptsTheScalarItAskedFor() throws Exception {
        ThinkingResponse<String> response = leafHandler().parse("{\"final_answer\": true, \"answer\": \"" + CANARY + "\"}");
        assertEquals(CANARY, response.getAnswer(), "a string answer arrives as the declared type");
    }

    @Test
    public void anAbsentAnswerIsTheLoopsBusinessNotAShapeViolation() throws Exception {
        ThinkingResponse<Answer> nulled = compositeHandler().parse("{\"final_answer\": false, \"answer\": null}");
        assertNull(nulled.getAnswer(), "an explicit null answer is no answer yet, not a wrong shape");
        ThinkingResponse<Answer> absent = compositeHandler().parse("{\"final_answer\": false}");
        assertNull(absent.getAnswer(), "an omitted answer is no answer yet, not a wrong shape");
    }

    @Test
    public void anAbsentAnswerIsAlsoNoViolationForALeafSchema() throws Exception {
        ThinkingResponse<String> nulled = leafHandler().parse("{\"final_answer\": false, \"answer\": null}");
        assertNull(nulled.getAnswer(), "an explicit null answer is no answer yet, whatever the schema declared");
        ThinkingResponse<String> absent = leafHandler().parse("{\"final_answer\": false}");
        assertNull(absent.getAnswer(), "an omitted answer is no answer yet, whatever the schema declared");
    }

    @Test
    public void theLeafRefusalAlsoNamesTheParameterAndTheAcceptedType() {
        InvalidInputException refused = assertThrows(InvalidInputException.class,
                () -> leafHandler().parse("{\"final_answer\": true, \"answer\": {\"" + KEY_CANARY + "\": 1}}"));
        assertEquals("answer", refused.getParameterName(), "the refusal names the field the schema declares");
        assertTrue(refused.getValidationRule().contains("String"),
                "the rule names the declared type that is accepted: " + refused.getValidationRule());
    }

    @Test
    public void anEmptyStringIsAValueAStringCanHold() throws Exception {
        ThinkingResponse<String> response = leafHandler().parse("{\"final_answer\": true, \"answer\": \"\"}");
        assertEquals("", response.getAnswer(), "an empty string is an instance of the declared type, so the shape check passes it");
    }

    /**
     * The published schema marks the answer's {@code value} field REQUIRED. An object carrying
     * none of its required fields is the right SHAPE, so the shape check passes it and the
     * missing field is reported by validation instead - which is where the caller of this
     * handler acts on it, appending a correction and asking again. Validation covers the
     * answer's own schema, not only the envelope's, or the requirement would be published to
     * the model and enforced by nobody.
     */
    @Test
    public void anAnswerMissingEveryRequiredFieldFailsValidation() throws Exception {
        ThinkingResponse<Answer> response = compositeHandler().parse("{\"final_answer\": true, \"answer\": {}}");
        assertInstanceOf(Answer.class, response.getAnswer(), "an empty object is the shape the schema asked for");
        List<String> errors = compositeHandler().getValidationErrors(response);
        assertFalse(errors.isEmpty(), "the answer's own required field must be reported: " + errors);
        assertTrue(String.join(" ", errors).contains("value"), "the report names the missing field: " + errors);
    }

    @Test
    public void aToolCallingTurnIsJudgedOnTheEnvelopeAlone() throws Exception {
        ThinkingResponse<Answer> response = compositeHandler().parse("{\"final_answer\": false}");
        assertTrue(compositeHandler().getValidationErrors(response).isEmpty(),
                "a turn with no answer yet has no answer to validate");
    }

    @Test
    public void anEnumRefusalNamesTheAcceptedValues() {
        InvalidInputException refused = assertThrows(InvalidInputException.class,
                () -> compositeHandler().parse("{\"final_answer\": true, \"answer\": {\"value\": \"v\", \"verdict\": \"" + CANARY + "\"}}"));
        assertTrue(refused.getValidationRule().contains("CONFIRMED") && refused.getValidationRule().contains("REFUTED"),
                "a model that is told only that its value was wrong cannot fix it: " + refused.getValidationRule());
        assertFalse(refused.getLLMMessage().contains(CANARY), "and the accepted values replace the echo of the value sent");
    }

    @Test
    public void anObjectWithUnknownFieldsIsStillTheShapeTheSchemaAskedFor() throws Exception {
        ThinkingResponse<Answer> response = compositeHandler()
                .parse("{\"final_answer\": true, \"answer\": {\"value\": \"" + CANARY + "\", \"" + KEY_CANARY + "\": 1}}");
        assertInstanceOf(Answer.class, response.getAnswer(),
                "an undeclared property inside the answer is the decoder's business, not this check's");
    }

    /**
     * The answer type the composite schema publishes. It carries a declared integer and a
     * declared enum as well as a string, because the decoders for those two are the ones that
     * quote the offending value when they refuse it, and a fixture of strings alone can never
     * reach them.
     */
    @LLMDescription("A resolved value")
    public static class Answer extends ThinkerOutput<SimpleReasoning> {
        @LLMRequired
        @LLMDescription("The value")
        private String value;
        @LLMDescription("How many were found")
        private Integer count;
        @LLMDescription("The verdict reached")
        private Verdict verdict;

        public Answer() {
            super();
            setReasoning(new SimpleReasoning());
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public Integer getCount() {
            return count;
        }

        public void setCount(Integer count) {
            this.count = count;
        }

        public Verdict getVerdict() {
            return verdict;
        }

        public void setVerdict(Verdict verdict) {
            this.verdict = verdict;
        }
    }

    public enum Verdict {
        CONFIRMED,
        REFUTED
    }
}
