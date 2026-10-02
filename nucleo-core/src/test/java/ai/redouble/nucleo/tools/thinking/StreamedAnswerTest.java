/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The final answer a top-level thinker streams is the typed answer itself: a watcher of the
 * content stream reads its fields as JSON, never a class name and a hash.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
public class StreamedAnswerTest {
    private static final Identifiable TEST_ROOT = Job.workflow("test-user", "streamed-answer-test");

    @BeforeAll
    static void boot() {
        JobDispatcher.getInstance().start();
    }

    @Test
    void aTypedAnswerStreamsAsItsJson() throws Exception {
        BlockingQueue<String> streamed = new LinkedBlockingQueue<>();
        MessageBus.Subscription subscription = JobDispatcher.getInstance().subscribe(new JobObserver<ContentStreamEvent>() {
            @Override
            public void observe(ContentStreamEvent event) {
                if (event.chunk() != null && event.chunk().isLast()) {
                    streamed.add(event.chunk().content());
                }
            }
        }, ContentStreamEvent.class);
        try {
            Answer answer = new Answer();
            answer.setText("ninety-eight days");
            JobDispatcher.getInstance().submit(new StreamHarness(answer)).get();
            String content = streamed.poll(5, TimeUnit.SECONDS);
            assertNotNull(content, "the answer was streamed as one done chunk");
            assertFalse(content.contains("@"), "never a class name and a hash: " + content);
            assertEquals("ninety-eight days", NucleoJsonSerializer.readTree(content).get("text").asText(), "the answer's own field, readable from the chunk");
        }
        finally {
            subscription.unsubscribe();
        }
    }

    /** Runs the thinker's streaming inside a real job, so it publishes on a live JobContext. */
    private static class StreamHarness extends AbstractDoer<Void, Void> {
        private final Answer answer;

        StreamHarness(Answer answer) {
            super(TEST_ROOT, "stream-harness");
            this.answer = answer;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Void execute(JobContext<Void> context) throws LLMReadableCheckedException {
            new AnsweringThinker(TEST_ROOT).streamAnswer(answer, (JobContext<Answer>) (JobContext<?>) context);
            return null;
        }
    }

    private static class AnsweringThinker extends SingleObjectiveThinker<VoidThinkerInput, Answer> {
        AnsweringThinker(Identifiable parent) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of();
        }
    }

    public static class Answer extends ThinkerOutput<SimpleReasoning> {
        private String text;

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}
