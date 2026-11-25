/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.artifacts.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins how the summary of an artifact's long field is made. The annotation alone decides
 * the way: the fixed phrase, a cut for content that must not be paraphrased, a model
 * otherwise. A summary is made once and kept on the artifact for every later write. A
 * model is asked only by {@link NucleoJsonSerializer#makeSummaries}; a write that comes
 * before it cuts the text and keeps nothing, and so does a making that fails. A field of
 * any type is measured and summarized as the JSON it is written as. An artifact shared by
 * many threads asks for one summary once.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public class NucleoJsonSerializerSummarizationTest {
    private static final String LONG = "a very long body of text that exceeds the threshold ".repeat(8);

    @TypeAlias("test:longtext")
    public static class Doc extends AbstractArtifact {
        @LLMSummarizable(value = "test text", threshold = 10)
        private String body;
        @LLMSummarizable(value = "a formula", threshold = 10, staticSummary = "a formula")
        private String formula;
        @LLMSummarizable(value = "exact wording", threshold = 10, llmSafe = false)
        private String exact;
        @LLMSummarizable(value = "lines of a log", threshold = 20)
        private List<String> lines;
        @LLMSummarizable(value = "a tree", threshold = 20)
        private JsonNode tree;
        @LLMSummarizable(value = "a document held by this one", threshold = 20)
        private Doc inner;

        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
        public String getFormula() { return formula; }
        public void setFormula(String formula) { this.formula = formula; }
        public String getExact() { return exact; }
        public void setExact(String exact) { this.exact = exact; }
        public List<String> getLines() { return lines; }
        public void setLines(List<String> lines) { this.lines = lines; }
        public JsonNode getTree() { return tree; }
        public void setTree(JsonNode tree) { this.tree = tree; }
        public Doc getInner() { return inner; }
        public void setInner(Doc inner) { this.inner = inner; }
    }

    /** Stands in for a model: answers with a marker and remembers every text it was given. */
    private static class RecordingModel implements Summarizer {
        final List<String> texts = new CopyOnWriteArrayList<>();

        @Override
        public String summarize(String text, LLMSummarizable annotation) {
            texts.add(text);
            return "[MODEL of " + annotation.value() + "]";
        }
    }

    private static LLMSummarizable annotation(String field) throws Exception {
        return Doc.class.getDeclaredField(field).getAnnotation(LLMSummarizable.class);
    }

    private static String cut(String text, String field) throws Exception {
        return new TruncatingSummarizer().summarize(text, annotation(field));
    }

    private static JsonNode written(Doc doc) throws Exception {
        return NucleoJsonSerializer.readTree(NucleoJsonSerializer.writeSummarized(doc));
    }

    @Test
    public void aSummaryIsMadeOnceByTheWayItsAnnotationNamesAndKeptForEveryWrite() throws Exception {
        Doc doc = new Doc();
        doc.setBody(LONG);
        doc.setFormula("C".repeat(300));
        doc.setExact(LONG);
        RecordingModel model = new RecordingModel();
        NucleoJsonSerializer.makeSummaries(doc, model);
        NucleoJsonSerializer.makeSummaries(doc, model);
        JsonNode first = written(doc);
        assertEquals("[MODEL of test text]", first.get("body").asText(), "a field with no other way named is summarized by a model");
        assertEquals("[SUMMARY: 300 chars] a formula", first.get("formula").asText(), "a fixed phrase stands for the value, with the value's length");
        assertEquals(cut(LONG, "exact"), first.get("exact").asText(), "content that must not be paraphrased is cut");
        assertEquals(List.of(LONG), model.texts, "the model was asked once, for the one field that needs it, over two makings");
        assertEquals(first, written(doc), "a later write shows the same summaries");
        assertEquals(Set.of("body", "formula", "exact"), doc.getSummaries().all().keySet(), "each summary is kept on the artifact under its field's name");
        assertEquals(LONG, doc.getBody(), "the field keeps its full value");
    }

    @Test
    public void aWriteBeforeAnyModelCutsAndKeepsNothing() throws Exception {
        Doc doc = new Doc();
        doc.setBody(LONG);
        assertEquals(cut(LONG, "body"), written(doc).get("body").asText(), "with no model to ask, the text is cut for this write");
        assertNull(doc.getSummaries().get("body"), "and the cut is not kept, so a model is still asked later");
        NucleoJsonSerializer.makeSummaries(doc, new RecordingModel());
        assertEquals("[MODEL of test text]", written(doc).get("body").asText(), "the model's summary replaces the cut once it is made");
    }

    @Test
    public void aModelThatFailsKeepsNothingAndIsAskedAgain() throws Exception {
        Doc doc = new Doc();
        doc.setBody(LONG);
        NucleoJsonSerializer.makeSummaries(doc, (text, annotation) -> {
            throw new IllegalStateException("the summarization job failed");
        });
        assertNull(doc.getSummaries().get("body"), "a failed making keeps nothing");
        assertEquals(cut(LONG, "body"), written(doc).get("body").asText(), "and the write that follows cuts");
        NucleoJsonSerializer.makeSummaries(doc, (text, annotation) -> null);
        assertNull(doc.getSummaries().get("body"), "a model that returns nothing keeps nothing either");
        NucleoJsonSerializer.makeSummaries(doc, new RecordingModel());
        assertEquals("[MODEL of test text]", doc.getSummaries().get("body"), "the next making asks again and keeps the answer");
    }

    @Test
    public void aTextNoLongerThanTheThresholdIsWrittenWhole() throws Exception {
        Doc doc = new Doc();
        doc.setBody("short");
        RecordingModel model = new RecordingModel();
        NucleoJsonSerializer.makeSummaries(doc, model);
        assertEquals("short", written(doc).get("body").asText());
        assertTrue(model.texts.isEmpty(), "nothing under the threshold reaches a model");
        assertTrue(doc.getSummaries().all().isEmpty());
    }

    @Test
    public void aFieldThatIsNoTextIsMeasuredAndSummarizedAsItsJson() throws Exception {
        Doc brief = new Doc();
        brief.setLines(List.of("one", "two"));
        brief.setTree(JsonNodeFactory.instance.objectNode().put("k", "v"));
        JsonNode whole = written(brief);
        assertEquals("[\"one\",\"two\"]", whole.get("lines").toString(), "13 characters of JSON, under the threshold of 20");
        assertEquals("{\"k\":\"v\"}", whole.get("tree").toString());
        Doc doc = new Doc();
        doc.setLines(List.of("the first line of a log", "the second line of a log"));
        doc.setTree(JsonNodeFactory.instance.objectNode().put("key", "a value long enough"));
        RecordingModel model = new RecordingModel();
        NucleoJsonSerializer.makeSummaries(doc, model);
        JsonNode summarized = written(doc);
        assertEquals("[MODEL of lines of a log]", summarized.get("lines").asText(), "a long list is shown as one summary");
        assertEquals("[MODEL of a tree]", summarized.get("tree").asText());
        assertEquals(Set.of("[\"the first line of a log\",\"the second line of a log\"]", "{\"key\":\"a value long enough\"}"),
                     Set.copyOf(model.texts), "the model is given the JSON the serializer writes for the value, never its toString()");
    }

    @Test
    public void aFieldThatHoldsAnArtifactIsSummarizedFromJsonThatHoldsThatArtifactsSummaries() throws Exception {
        Doc inner = new Doc();
        inner.setBody(LONG);
        Doc outer = new Doc();
        outer.setInner(inner);
        RecordingModel model = new RecordingModel();
        NucleoJsonSerializer.makeSummaries(outer, model);
        assertEquals("[MODEL of a document held by this one]", written(outer).get("inner").asText());
        assertEquals("[MODEL of test text]", inner.getSummaries().get("body"), "the held artifact's own summary is made first and kept on it");
        String givenForInner = model.texts.get(1);
        assertTrue(givenForInner.contains("[MODEL of test text]"), "the JSON summarized for the outer field already shows the inner summary: " + givenForInner);
        assertFalse(givenForInner.contains(LONG), "and never the inner field's full text");
    }

    /** A model that waits for the test before answering, and counts its calls. */
    private static class GatedModel implements Summarizer {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean fail;

        @Override
        public String summarize(String text, LLMSummarizable annotation) {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await();
            }
            catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            if (fail) {
                throw new IllegalStateException("the summarization job failed");
            }
            return "[MODEL]";
        }
    }

    /** Starts the given number of threads that each make the summaries of one artifact. */
    private static List<Thread> makers(int count, Doc doc, Summarizer model) {
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Thread thread = new Thread(() -> NucleoJsonSerializer.makeSummaries(doc, model), "maker-" + i);
            thread.start();
            threads.add(thread);
        }
        return threads;
    }

    /** Waits until all but one of the threads are parked behind the one that is making. */
    private static void awaitWaiters(List<Thread> threads) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (threads.stream().filter(thread -> thread.getState() == Thread.State.WAITING).count() < threads.size()) {
            assertTrue(System.nanoTime() < deadline, "the threads never came to wait on the one making");
            Thread.sleep(5);
        }
    }

    @Test
    public void fiftyThreadsAskAModelOnce() throws Exception {
        Doc doc = new Doc();
        doc.setBody(LONG);
        GatedModel model = new GatedModel();
        List<Thread> threads = makers(50, doc, model);
        assertTrue(model.entered.await(30, TimeUnit.SECONDS));
        // the maker waits on the gate and the other forty-nine on the maker
        awaitWaiters(threads);
        model.release.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(1, model.calls.get(), "one making serves every thread that needed the summary");
        assertEquals("[MODEL]", doc.getSummaries().get("body"));
    }

    @Test
    public void whenTheOneMakingFailsEveryWaiterCutsAndNothingIsKept() throws Exception {
        Doc doc = new Doc();
        doc.setBody(LONG);
        GatedModel model = new GatedModel();
        model.fail = true;
        List<Thread> threads = makers(50, doc, model);
        assertTrue(model.entered.await(30, TimeUnit.SECONDS));
        awaitWaiters(threads);
        model.release.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(1, model.calls.get(), "a waiter does not make the summary itself when the making it waited for failed");
        assertNull(doc.getSummaries().get("body"), "nothing is kept");
        assertEquals(cut(LONG, "body"), written(doc).get("body").asText());
    }

    @Test
    public void aWaiterThatIsInterruptedStopsWaitingAndKeepsItsInterruptFlag() throws Exception {
        Doc doc = new Doc();
        doc.setBody(LONG);
        GatedModel model = new GatedModel();
        Thread maker = makers(1, doc, model).get(0);
        assertTrue(model.entered.await(30, TimeUnit.SECONDS));
        AtomicBoolean flagAfter = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            NucleoJsonSerializer.makeSummaries(doc, model);
            flagAfter.set(Thread.currentThread().isInterrupted());
        }, "waiter");
        waiter.start();
        awaitWaiters(List.of(maker, waiter));
        waiter.interrupt();
        waiter.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(waiter.isAlive(), "the interrupted waiter returned while the making was still running");
        assertTrue(flagAfter.get(), "its interrupt flag is left set for whoever cancels the job");
        assertNull(doc.getSummaries().get("body"));
        model.release.countDown();
        maker.join();
        assertEquals("[MODEL]", doc.getSummaries().get("body"), "the making it left goes on and keeps its answer");
        assertEquals(1, model.calls.get());
    }

    @Test
    public void anInterruptedThreadAsksNoModel() throws Exception {
        Doc doc = new Doc();
        doc.setBody(LONG);
        RecordingModel model = new RecordingModel();
        Thread.currentThread().interrupt();
        try {
            NucleoJsonSerializer.makeSummaries(doc, model);
            assertTrue(Thread.currentThread().isInterrupted(), "the flag is left set");
        }
        finally {
            Thread.interrupted();
        }
        assertTrue(model.texts.isEmpty(), "no summary is asked for on a thread that is being cancelled");
        assertNull(doc.getSummaries().get("body"));
    }
}
