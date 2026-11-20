/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.observability.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A chat's answer is an object, and the artifacts that leave the chat with it are the ones the
 * answer selects: those it names in {@code artifact_refs} and those whose reference it writes
 * in a field. The conversation's registry holds three artifacts in every case here, so what
 * is published is shown to be the selection and never the registry.
 *
 * <p>The client is a stub behind the call's {@code client(resources)} seam; each turn, from
 * the inbox to the published {@link ContentStreamEvent}, runs for real through the dispatcher.
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-14)
 */
public class ChatAnswerSelectionTest {

    @BeforeAll
    static void startDispatcher() {
        JobDispatcher.getInstance().start();
    }

    @Test
    void anAnswerThatNamesOneOfThreeArtifacts_publishesThatOne() throws Exception {
        SelectingChat<ChatAnswer> chat = answered(ChatAnswer.class,
                refs -> "{\"final_answer\": true, \"answer\": {\"message_text\": \"See the second.\", \"artifact_refs\": [\"" + refs.get(1) + "\"]}}");
        assertEquals("See the second.", chat.published.chunk().content(), "the chunk holds the answer's text");
        assertEquals(Set.of(chat.refs.get(1)), chat.published.getArtifacts().keySet(), "the one artifact the answer named, keyed by its reference");
        assertSame(chat.held.get(1), chat.published.getArtifacts().get(chat.refs.get(1)), "the registry's own object");
        assertSame(chat.delivered, chat.published.getAnswer(), "the event carries the answer object");
        assertEquals(List.of(chat.refs.get(1)), chat.delivered.getArtifactRefs());
    }

    /**
     * An exchange whose closing call still asks for tools has no answer and fails: the turn
     * ends in the failure, and the conversation is rolled back to the person's message, so
     * the closing call's tool calls, which have no results, reach neither the store nor the
     * next request. Saved as it stood, the conversation would be refused by a provider on
     * every later turn.
     */
    @Test
    void aClosingCallThatStillAsksForTools_failsTheExchangeAndLeavesNoUnansweredToolCall() {
        Identifiable workflow = Job.workflow("chat-closing-call", "chat-closing-call");
        SelectingChat<ChatAnswer> chat = new SelectingChat<>(workflow, ChatAnswer.class,
                refs -> "{\"final_answer\": false, \"tool_calls\": [{\"tool_name\": \"no_such_tool\", \"input\": {}}]}", null);
        chat.setMaxIterationsPerMessage(1);
        assertTrue(chat.addMessage("the question"));
        ExecutionException failed = assertThrows(ExecutionException.class, () -> JobDispatcher.getInstance().submit(chat).get(30, TimeUnit.SECONDS),
                "a turn whose exchange produced no answer ends as a failure");
        assertTrue(String.valueOf(failed.getCause().getMessage()).contains("closing call still produced no answer"), String.valueOf(failed.getCause()));
        assertTrue(chat.calls.get() >= 2, "the budgeted call and the closing call were both made: " + chat.calls.get());
        List<Message> messages = chat.conversation.getMessages();
        assertTrue(messages.get(messages.size() - 1) instanceof OutgoingMessage, "the conversation ends at the person's turn, no assistant turn after it");
        assertTrue(messages.stream().noneMatch(m -> m instanceof IncomingMessage), "no reply of the failed exchange is kept, the closing call's tool calls least of all");
    }

    @Test
    void anAnswerThatNamesNoArtifact_publishesNone() throws Exception {
        SelectingChat<ChatAnswer> chat = answered(ChatAnswer.class,
                refs -> "{\"final_answer\": true, \"answer\": {\"message_text\": \"Nothing to show.\"}}");
        assertNull(chat.published.getArtifacts(), "three artifacts are registered and none was selected");
        assertTrue(chat.delivered.getArtifacts().isEmpty());
    }

    @Test
    void aReferenceWrittenOnlyInTheText_isSelected() throws Exception {
        SelectingChat<ChatAnswer> chat = answered(ChatAnswer.class,
                refs -> "{\"final_answer\": true, \"answer\": {\"message_text\": \"As " + refs.get(2) + " shows.\"}}");
        assertEquals(Set.of(chat.refs.get(2)), chat.published.getArtifacts().keySet(), "a reference in message_text selects its artifact");
        assertEquals(List.of(chat.refs.get(2)), chat.delivered.getArtifactRefs(), "and joins the declared references");
    }

    @Test
    void aReferenceWrittenInASubclassField_isSelected_liveAndOnReplay() throws Exception {
        SelectingChat<VerdictAnswer> chat = answered(VerdictAnswer.class,
                refs -> "{\"final_answer\": true, \"answer\": {\"message_text\": \"Settled.\", \"verdict\": \"yes, per " + refs.get(1) + "\"}}");
        assertEquals(Set.of(chat.refs.get(1)), chat.published.getArtifacts().keySet(), "a reference in a field the subclass added selects its artifact");
        VerdictAnswer replayed = ChatAnswer.stored(chat.conversation.getMessages().get(1).getRawContent(), VerdictAnswer.class, chat.conversation.getArtifactRegistry());
        assertEquals(chat.published.getArtifacts(), replayed.artifactsByRef(), "and the replay of the stored reply selects the same");
    }

    @Test
    void anAnswerThatNamesAReferenceTheRegistryDoesNotHold_isRefusedAndAskedAgain() throws Exception {
        SelectingChat<ChatAnswer> chat = answered(ChatAnswer.class,
                refs -> "{\"final_answer\": true, \"answer\": {\"message_text\": \"One named.\", \"artifact_refs\": [\"" + refs.get(0) + "\"]}}",
                "{\"final_answer\": true, \"answer\": {\"message_text\": \"One invented.\", \"artifact_refs\": [\"«artifact:link~nowhere»\"]}}");
        assertTrue(chat.calls.get() > 1, "the answer with the unknown reference was refused and the model was asked again");
        assertEquals("One named.", chat.delivered.getMessageText(), "the answer that left is the corrected one");
        assertEquals(Set.of(chat.refs.get(0)), chat.published.getArtifacts().keySet(), "with the artifact its reference names");
    }

    @Test
    void aStoredAnswerThatNamesAReferenceItsRegistryDoesNotHold_readsWithTheArtifactsItsOtherReferencesSelect() throws Exception {
        ArtifactRegistry registry = new ArtifactRegistry();
        LinkArtifact link = new LinkArtifact();
        String ref = ArtifactRegistry.normalizeToKey(registry.register(link));
        ChatAnswer replayed = ChatAnswer.stored("{\"final_answer\": true, \"answer\": {\"message_text\": \"Two named.\", \"artifact_refs\": [\"" + ref
                + "\", \"«artifact:link~nowhere»\"]}}", ChatAnswer.class, registry);
        assertEquals(Set.of(link), replayed.getArtifacts(), "an answer stored before such a reference was refused replays as it was shown");
        assertEquals(List.of(ref, "«artifact:link~nowhere»"), replayed.getArtifactRefs(), "and its list is what was stored");
    }

    @Test
    void anAnswerSubclass_keepsItsOwnFields_andATypedArtifactFieldIsTheRegistrysObject() throws Exception {
        SelectingChat<VerdictAnswer> chat = answered(VerdictAnswer.class,
                refs -> "{\"final_answer\": true, \"answer\": {\"message_text\": \"Settled.\", \"verdict\": \"yes\", \"source\": {\"artifact_ref\": \"" + refs.get(0) + "\", \"url\": \"https://invented.example\"}}}");
        assertEquals("yes", chat.delivered.getVerdict(), "the subclass's own field arrives");
        assertSame(chat.held.get(0), chat.delivered.getSource(), "a typed artifact field holds the registry's object, never what the model wrote beside the reference");
        assertSame(chat.delivered, chat.published.getAnswer());
    }

    @Test
    void anAnswerWrittenAsABareString_isRefusedAndAskedAgain() throws Exception {
        SelectingChat<ChatAnswer> chat = answered(ChatAnswer.class,
                refs -> "{\"final_answer\": true, \"answer\": {\"message_text\": \"Now an object.\"}}",
                "{\"final_answer\": true, \"answer\": \"A flattened answer.\"}");
        assertEquals("Now an object.", chat.delivered.getMessageText(), "the exchange went on to the answer the schema asks for");
        assertTrue(chat.calls.get() > 1, "the string was refused and the model was asked again");
    }

    @Test
    void aStoredAnswerReadAgainstItsRegistry_yieldsTheLiveSelection() throws Exception {
        SelectingChat<ChatAnswer> chat = answered(ChatAnswer.class,
                refs -> "{\"final_answer\": true, \"answer\": {\"message_text\": \"As " + refs.get(2) + " shows.\", \"artifact_refs\": [\"" + refs.get(0) + "\"]}}");
        String storedReply = chat.conversation.getMessages().get(1).getRawContent();
        ChatAnswer replayed = ChatAnswer.stored(storedReply, ChatAnswer.class, chat.conversation.getArtifactRegistry());
        assertEquals(chat.delivered.getMessageText(), replayed.getMessageText());
        assertEquals(chat.delivered.getArtifactRefs(), replayed.getArtifactRefs());
        assertEquals(chat.published.getArtifacts(), replayed.artifactsByRef(), "the replay shows the artifacts the live exchange showed");
    }

    @Test
    void aMapperWithNoNamingOfItsOwn_writesTheAnswersFieldsInSnakeCase() throws Exception {
        VerdictAnswer answer = new VerdictAnswer();
        answer.setMessageText("Settled.");
        answer.setVerdict("yes");
        answer.addArtifactRef("link~abc");
        com.fasterxml.jackson.databind.JsonNode written = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(answer);
        assertEquals("Settled.", written.path("message_text").asText(), "the field a front end relies on: " + written);
        assertEquals("«artifact:link~abc»", written.path("artifact_refs").path(0).asText(), "and the other one: " + written);
        assertEquals("yes", written.path("verdict").asText(), "a subclass's field beside them");
        assertFalse(written.has("messageText") || written.has("artifactRefs") || written.has("artifacts"), "no second spelling, and the resolved objects are not part of the answer: " + written);
    }

    @Test
    void aStoredMessageThatIsNoAnswer_readsAsNull() throws Exception {
        ArtifactRegistry registry = new ArtifactRegistry();
        assertNull(ChatAnswer.stored("{\"final_answer\": false, \"tool_calls\": [{\"tool_name\": \"t\", \"input\": {}}]}", ChatAnswer.class, registry), "a turn that called tools");
        assertNull(ChatAnswer.stored("Let me look that up.", ChatAnswer.class, registry), "a turn with no reply object");
        assertNull(ChatAnswer.stored(null, ChatAnswer.class, registry), "a turn with no text");
    }

    @Test
    void anAnswerStoredAsAString_readsAsItsTextWithNoArtifacts() throws Exception {
        ArtifactRegistry registry = new ArtifactRegistry();
        LinkArtifact link = new LinkArtifact();
        String ref = registry.register(link);
        ChatAnswer replayed = ChatAnswer.stored("{\"final_answer\": true, \"answer\": \"See " + ref + ".\"}", ChatAnswer.class, registry);
        assertEquals("See " + ref + ".", replayed.getMessageText(), "the string is the answer's text");
        assertTrue(replayed.getArtifacts().isEmpty(), "an answer from before the answer was an object selected nothing");
        assertFalse(replayed.hasArtifactRefs());
    }

    private static <A extends ChatAnswer> SelectingChat<A> answered(Class<A> answerClass, Function<List<String>, String> reply) throws Exception {
        return answered(answerClass, reply, null);
    }

    /** Runs one exchange; {@code firstReply}, when given, is what the model says before {@code reply}. */
    private static <A extends ChatAnswer> SelectingChat<A> answered(Class<A> answerClass, Function<List<String>, String> reply, String firstReply) throws Exception {
        Identifiable workflow = Job.workflow("chat-answer-test-user", "chat-answer-test");
        SelectingChat<A> chat = new SelectingChat<>(workflow, answerClass, reply, firstReply);
        CountDownLatch answerPublished = new CountDownLatch(1);
        MessageBus.Subscription subscription = JobDispatcher.getInstance().subscribe(new JobObserver<ContentStreamEvent>() {
            @Override
            public Predicate<ContentStreamEvent> getPredicate() {
                return event -> workflow.getWorkflowId().equals(event.snapshot().getWorkflowId());
            }

            @Override
            public void observe(ContentStreamEvent event) {
                chat.published = event;
                answerPublished.countDown();
            }
        }, ContentStreamEvent.class);
        try {
            assertTrue(chat.addMessage("the question"), "the message is queued before the turn starts");
            JobDispatcher.getInstance().submit(chat).get(30, TimeUnit.SECONDS);
            assertTrue(answerPublished.await(30, TimeUnit.SECONDS), "the chat published its answer");
        }
        finally {
            subscription.unsubscribe();
        }
        return chat;
    }

    /** An application's answer: a field of its own and a typed artifact field beside the two every chat answer has. */
    public static class VerdictAnswer extends ChatAnswer {
        private String verdict;
        private LinkArtifact source;

        public String getVerdict() {
            return verdict;
        }

        public void setVerdict(String verdict) {
            this.verdict = verdict;
        }

        public LinkArtifact getSource() {
            return source;
        }

        public void setSource(LinkArtifact source) {
            this.source = source;
        }
    }

    /** A chat whose conversation holds three artifacts and whose model replies with what the test wrote. */
    private static final class SelectingChat<A extends ChatAnswer> extends ChatThinker<A> {
        private final StandardModelSpec spec = new StandardModelSpec();
        private final Function<List<String>, String> reply;
        private final String firstReply;
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        final List<LinkArtifact> held = new ArrayList<>();
        final List<String> refs = new ArrayList<>();
        ConversationContext conversation;
        A delivered;
        volatile ContentStreamEvent published;

        SelectingChat(Identifiable parent, Class<A> answerClass, Function<List<String>, String> reply, String firstReply) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT), answerClass);
            this.reply = reply;
            this.firstReply = firstReply;
            spec.setId("chat-answer-fake");
            spec.setIdentity("chat-answer-fake");
            spec.setProviderKey("openai");
            spec.setWireModelId("chat-answer-fake");
            spec.setGrade(Grade.SMALL);
            spec.setMaxContextTokens(100_000);
            spec.setMaxOutputTokens(32_000);
            spec.setTpm(1_000_000);
            spec.setRpm(10_000);
            pinModel(spec);
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of();
        }

        @Override
        protected void initializeConversation(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
            this.conversation = conversation;
            for (int i = 0; i < 3; i++) {
                LinkArtifact link = new LinkArtifact();
                link.setTitle("page " + i);
                link.setUrl("https://held.example/" + i);
                held.add(link);
                refs.add(ArtifactRegistry.normalizeToKey(conversation.getArtifactRegistry().register(link)));
            }
        }

        @Override
        protected <R> LLMCall<R> newLLMCall(Identifiable parent, ConversationContext conversation) {
            boolean first = calls.incrementAndGet() == 1;
            LLMCallCorrectionTest.CannedClient client = new LLMCallCorrectionTest.CannedClient(first && firstReply != null ? firstReply : reply.apply(refs));
            client.setModel(spec);
            LLMCall<R> call = new LLMCall<>(parent, conversation) {
                @Override
                protected LLMClient client(JobResources resources) {
                    return client;
                }
            };
            call.pinModel(spec);
            return call;
        }

        @Override
        protected void streamResponse(A answer, JobContext<VoidThinkerOutput> context) {
            delivered = answer;
            super.streamResponse(answer, context);
        }
    }
}
