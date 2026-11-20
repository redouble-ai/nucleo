/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.registry.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A thinker publishes a reply's reasoning as a notification when the reply carries one, and
 * publishes nothing for a reply without reasoning: a watcher never receives a notification
 * with no message.
 *
 * @author Andrey Santrosyan
 * @since 0.1.4 (2026-11-19)
 */
public class ReasoningNotificationTest {
    private static final Identifiable TEST_ROOT = Job.workflow("test-user", "reasoning-notification-test");
    private static final String TITLE = "Reasoning";
    private static final String MARKER = "End of reply";

    @BeforeAll
    static void boot() {
        JobDispatcher.getInstance().start();
    }

    @Test
    void aReplyWithAThoughtPublishesItAsTheMessage() throws Exception {
        ThinkingResponse<Answer> reply = new ThinkingResponse<>();
        reply.getReasoning().setThought("The order shipped on the 3rd, so it is late");
        List<UserNotificationEvent> published = publishedFor(reply);
        assertEquals(2, published.size(), "the reasoning and the marker that ends the reply");
        assertEquals(TITLE, published.get(0).title(), "the reasoning is published under the title the thinker gave");
        assertEquals("The order shipped on the 3rd, so it is late", published.get(0).getHumanMessage(), "the message is the thought");
    }

    @Test
    void aReplyWithNoReasoningPublishesNothing() throws Exception {
        List<UserNotificationEvent> published = publishedFor(new ThinkingResponse<>());
        assertEquals(1, published.size(), "the marker alone: a reply without reasoning publishes no notification");
        assertEquals(MARKER, published.get(0).title(), "the one notification is the marker");
    }

    @Test
    void aReplyWithAnEmptyThoughtPublishesNothing() throws Exception {
        ThinkingResponse<Answer> reply = new ThinkingResponse<>();
        reply.getReasoning().setThought("");
        List<UserNotificationEvent> published = publishedFor(reply);
        assertEquals(1, published.size(), "the marker alone: an empty thought is no reasoning");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aNativeThinkingBlockIsTheReasoningPublished() throws Exception {
        ThinkingResponseHandler<Answer> handler = new ThinkingResponseHandler<>(new ToolRegistry(),
                new PojoResponseHandler<ThinkingResponse<Answer>>((Class) ThinkingResponse.class),
                new PojoResponseHandler<>(Answer.class));
        ThinkingResponse<Answer> reply = handler.parse(List.of(new ThinkingBlock("The stock count is two days old", null), new TextBlock("{\"final_answer\": true, \"answer\": {\"text\": \"late\"}}")));
        List<UserNotificationEvent> published = publishedFor(reply);
        assertEquals(2, published.size(), "the reasoning and the marker that ends the reply");
        assertEquals("The stock count is two days old", published.get(0).getHumanMessage(), "the thinking block's text is the message");
    }

    /**
     * Publishes {@code reply}'s reasoning from inside a real job, then a marker, and returns
     * every notification of that job up to the marker, in order.
     */
    private static List<UserNotificationEvent> publishedFor(ThinkingResponse<Answer> reply) throws Exception {
        BlockingQueue<UserNotificationEvent> seen = new LinkedBlockingQueue<>();
        PublishHarness harness = new PublishHarness(reply);
        MessageBus.Subscription subscription = JobDispatcher.getInstance().subscribe(new JobObserver<UserNotificationEvent>() {
            @Override
            public void observe(UserNotificationEvent event) {
                if (harness.getJobId().equals(event.snapshot().getJobId())) {
                    seen.add(event);
                }
            }
        }, UserNotificationEvent.class);
        try {
            JobDispatcher.getInstance().submit(harness).get();
            List<UserNotificationEvent> published = new ArrayList<>();
            while (true) {
                UserNotificationEvent event = seen.poll(5, TimeUnit.SECONDS);
                assertNotNull(event, "the marker that ends the reply arrived");
                published.add(event);
                if (MARKER.equals(event.title())) {
                    return published;
                }
            }
        }
        finally {
            subscription.unsubscribe();
        }
    }

    /** Runs the thinker's publication inside a real job, so it publishes on a live JobContext. */
    private static class PublishHarness extends AbstractDoer<Void, Void> {
        private final ThinkingResponse<Answer> reply;

        PublishHarness(ThinkingResponse<Answer> reply) {
            super(TEST_ROOT, "publish-harness");
            this.reply = reply;
        }

        @Override
        public Void execute(JobContext<Void> context) throws LLMReadableCheckedException {
            new ReasoningThinker(TEST_ROOT).publishReasoning(reply, TITLE, context);
            context.publishUserNotification(UserNotificationEvent.Severity.INFO, MARKER, MARKER);
            return null;
        }
    }

    private static class ReasoningThinker extends SingleObjectiveThinker<VoidThinkerInput, Answer> {
        ReasoningThinker(Identifiable parent) {
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
