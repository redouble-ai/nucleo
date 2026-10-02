/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.secrets.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.builtin.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.slf4j.*;

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manual live probe of both transports of the dialect under the runtime, end to end. Not a
 * unit test - it costs tokens and needs a key - so it is a {@code main} that never runs in
 * the suite. Five checks, each reported PASS or FAIL with the evidence; the first two run
 * twice, once through the SDK and once through the framework's own HTTP client pointed at
 * OpenAI's API root under the same key, since the dialect is shared and both must carry it:
 * <ol>
 *   <li><b>Tool calling.</b> A chat thinker with the current-time tool, pinned to the SMALL
 *       entry, runs through the dispatcher and is asked for the exact timestamp the tool
 *       returns. A model cannot guess today's date, so the date in the answer is the proof
 *       the tool was called and its result read back; a call id of the provider's own
 *       minting in the persisted turns is the proof the call went through the native
 *       channel rather than the text envelope.</li>
 *   <li><b>Rehydration.</b> The conversation is persisted through a store that keeps only
 *       JSON text. A second thinker resumes the same conversation id, which the service can
 *       only satisfy by loading that text, and is asked what the tool said earlier without
 *       calling it again. The same date in the answer is the proof the tool turns survived
 *       the round trip - and, on this dialect, that the re-rendered call and its result were
 *       accepted back as message structure.</li>
 *   <li><b>Streaming.</b> The bare client streams a short answer; more than one chunk and
 *       the usage block from the final chunk are the proof.</li>
 *   <li><b>Truncation.</b> A tiny output budget against a long question must surface as the
 *       framework's truncation escalation, never as a silently short answer.</li>
 *   <li><b>Image.</b> A one-pixel image rides the request as multimodal content and the
 *       provider accepts the shape.</li>
 * </ol>
 * Run on a classpath carrying the runtime, this artifact's test classes, and a secret store
 * holding {@code openai-api-key}; the model is {@code gpt-5-mini} unless an argument names
 * another SMALL entry.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public final class LiveOpenAIChatProbe {
    private static final Logger log = LoggerFactory.getLogger(LiveOpenAIChatProbe.class);
    private static final String USER = "openai-chat-probe";

    private LiveOpenAIChatProbe() {
    }

    /** The probe's chat: the time tool and an instruction to answer with the tool's exact value. */
    @ToolName("openai_probe_chat")
    @ToolDescription(value = "Answers time questions with the current-time tool", readOnly = true)
    @ToolWeight(type = ToolType.THINKER, level = 0, min = 1, max = 4)
    public static class ProbeChat extends ChatThinker {
        final StringBuilder answer = new StringBuilder();

        public ProbeChat(Identifiable parent) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
            setMaxIterationsPerMessage(4);
        }

        @Override
        protected void initializeConversation(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
            super.initializeConversation(conversation, context);
            conversation.putMainObjective("instructions", "You answer questions about the current time by calling"
                    + " get_current_time and quoting its isoTimestamp verbatim. When asked what a tool said earlier,"
                    + " quote it from the conversation without calling any tool. Reply with the value only, no prose.");
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of(CurrentTimeTool.class);
        }

        @Override
        protected void streamResponse(String response, Map<String, Artifact> artifacts, JobContext<VoidThinkerOutput> context) {
            answer.append(response);
            super.streamResponse(response, artifacts, context);
        }
    }

    /**
     * The hand-rolled transport aimed at OpenAI itself: the same key the SDK uses, the public
     * API root as the endpoint. Registered by the provider scan like any provider on the
     * classpath, and served the probe's re-keyed copy of the entry under test.
     */
    public static class CompatibleViaOpenAI extends OpenAICompatibleProvider {
        static final String KEY = "openai-compatible-probe";
        static final String OPENAI_API_ROOT = "https://api.openai.com/v1";

        @Override
        public String key() {return KEY;}

        @Override
        public String credentialId() {return OpenAIProvider.SECRET_ID;}

        @Override
        protected OpenAICompatibleClient newClient() {
            return new OpenAICompatibleClient(WireApi.RESPONSES, OPENAI_API_ROOT, Secrets.configured().require(OpenAIProvider.SECRET_ID).secret());
        }
    }

    /** Every grade the probe touches served by the one SMALL entry under test. */
    public static class PinnedPicker extends OpenModelPicker {
        static ModelSpec pinned;

        @Override
        protected ModelSpec pick(Seat seat, Situation situation) {
            return pinned;
        }

        @Override
        public Grade ceiling() {
            return Grade.SMALL;
        }
    }

    /**
     * A store that keeps JSON text and nothing else, so a load is a real deserialization of
     * what a save wrote - the rehydration under test - and every other capability of the
     * contract answers as an empty store would.
     */
    public static class TextStore implements PersistentStore {
        final Map<String, String> json = new ConcurrentHashMap<>();
        final Map<String, Integer> saves = new ConcurrentHashMap<>();

        @Override
        public void createContext(ConversationContext context, Thinker<?, ?> thinker) {
            save(context.toSnapshot(), thinker.getUserId());
        }

        @Override
        public void save(ConversationPersistenceSnapshot snapshot, String user) {
            json.put(snapshot.getConversationId(), NucleoJsonSerializer.write(snapshot));
            saves.merge(snapshot.getConversationId(), 1, Integer::sum);
        }

        @Override
        public ConversationPersistenceSnapshot load(String conversationId, String user, Identifiable caller) {
            String text = json.get(conversationId);
            if (text == null) {
                return null;
            }
            try {
                return NucleoJsonSerializer.parse(text, ConversationPersistenceSnapshot.class);
            }
            catch (IOException e) {
                throw new IllegalStateException("the stored snapshot does not parse", e);
            }
        }

        @Override
        public boolean exists(String conversationId, String user) {return json.containsKey(conversationId);}

        @Override
        public boolean delete(String conversationId, String user) {return json.remove(conversationId) != null;}

        @Override
        public Long getLastModified(String conversationId, String user) {return json.containsKey(conversationId) ? System.currentTimeMillis() : null;}

        @Override
        public List<String> findContexts(ContextQuery query, String user) {return new ArrayList<>(json.keySet());}

        @Override
        public void saveWithMetadata(ConversationPersistenceSnapshot snapshot, Map<String, String> metadata, String user) {save(snapshot, user);}

        @Override
        public Map<String, String> getMetadata(String conversationId, String user) {return Map.of();}

        @Override
        public boolean archive(String conversationId, String user) {return false;}

        @Override
        public boolean restore(String conversationId, String user) {return false;}

        @Override
        public boolean isArchived(String conversationId, String user) {return false;}

        @Override
        public List<Instant> getVersionHistory(String conversationId, String user) {return List.of();}

        @Override
        public ConversationPersistenceSnapshot loadVersion(String conversationId, Instant versionTime, String user) {return load(conversationId, user, null);}

        @Override
        public int backup(String backupId, String user) {return 0;}

        @Override
        public Map<String, Object> getStats() {return Map.of("conversations", json.size());}
    }

    public static void main(String[] args) throws Exception {
        ModelSpec spec = Models.spec(args.length > 0 ? args[0] : "gpt-5-mini");
        ModelPickers.use(new PinnedPicker());
        DefaultComplianceEnvelope permitting = new DefaultComplianceEnvelope();
        permitting.setAllowDataShare(true);
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.sealComplianceEnvelope(permitting);
        TextStore store = new TextStore();
        ConversationService.getInstance().setPersistentStore(store);
        dispatcher.start();
        int failures = 0;
        try {
            String today = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE);
            // 1 and 2, through the SDK, then through the hand-rolled transport on the same entry re-keyed to it
            failures += toolCallingAndRehydration(dispatcher, store, spec, "sdk", today);
            failures += toolCallingAndRehydration(dispatcher, store, viaCompatibleTransport(spec), "compatible", today);
            PinnedPicker.pinned = spec;
            // 3. Streaming through the bare client
            OpenAISDKClient client = new OpenAISDKClient(WireApi.RESPONSES);
            client.setModel(spec);
            try {
                ConversationContext conversation = conversation(spec);
                ask(conversation, "Count from one to twenty as words separated by spaces.", 400);
                List<StreamChunk> chunks = new ArrayList<>();
                LLMResponse<String> response = client.streamResponse(new LLMRequest<>(conversation), chunks::add);
                String text = response.getResponseMessage().getRawContent();
                boolean pass = chunks.size() > 2 && text != null && text.contains("twenty") && response.getActualOutputTokens() != null;
                failures += pass ? 0 : 1;
                log.info("[ChatProbe] streaming: {} -> {} chunks, {} output tokens, \"{}\"", pass ? "PASS" : "FAIL", chunks.size(),
                        response.getActualOutputTokens(), text == null ? null : text.replace('\n', ' '));
            }
            catch (Exception e) {
                failures++;
                log.error("[ChatProbe] streaming: FAIL {}", chain(e));
            }
            // 4. Truncation surfaces as the escalation. On a reasoning-effort model the wire ceiling is
            // the declared budget plus the reasoning headroom the depth books, so the answer has to
            // outgrow the whole ceiling for the provider to stop it
            try {
                ConversationContext conversation = conversation(spec);
                ask(conversation, "Write a detailed 3000-word essay on the history of the bicycle, with at least twelve"
                        + " paragraphs, each on a different decade, naming inventors, models and cities.", 16);
                LLMResponse<String> response = client.singleResponse(new LLMRequest<>(conversation));
                failures++;
                log.error("[ChatProbe] truncation: FAIL a 16-token budget answered without escalation: stop {} \"{}\"",
                        response.getStopReason(), String.valueOf(response.getResponseMessage().getRawContent()).replace('\n', ' '));
            }
            catch (OutputTruncationRetryException e) {
                log.info("[ChatProbe] truncation: PASS escalated {}", e.getMessage());
            }
            catch (Exception e) {
                failures++;
                log.error("[ChatProbe] truncation: FAIL {}", chain(e));
            }
            // 5. An image in the request
            try {
                ConversationContext conversation = conversation(spec);
                OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
                message.setRole("user");
                message.setTimestamp(Instant.now());
                message.addImage(new ByteArrayInputStream(Base64.getDecoder().decode(ONE_RED_PIXEL_PNG)), "image/png", "a single pixel");
                message.addText("What color is this image? One word.");
                message.setRequestedOutputTokens(300);
                conversation.getMessages().add(message);
                LLMResponse<String> response = client.singleResponse(new LLMRequest<>(conversation));
                String text = response.getResponseMessage().getRawContent();
                boolean pass = text != null && !text.isBlank();
                failures += pass ? 0 : 1;
                log.info("[ChatProbe] image: {} -> \"{}\"", pass ? "PASS" : "FAIL", text == null ? null : text.replace('\n', ' '));
            }
            catch (Exception e) {
                failures++;
                log.error("[ChatProbe] image: FAIL {}", chain(e));
            }
        }
        finally {
            dispatcher.shutdown(10_000);
        }
        log.info("[ChatProbe] {} failure(s)", failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    /** The same entry served by the hand-rolled transport: a copy under its own id, keyed to the probe's provider. */
    private static ModelSpec viaCompatibleTransport(ModelSpec spec) {
        StandardModelSpec copy = NucleoJsonSerializer.convert(NucleoJsonSerializer.valueToTree(spec), StandardModelSpec.class);
        copy.setId(spec.getId() + "@" + CompatibleViaOpenAI.KEY);
        copy.setProviderKey(CompatibleViaOpenAI.KEY);
        Models.updateSpec(copy.getId(), copy);
        return copy;
    }

    /** Checks 1 and 2 on one transport; the number of failures. */
    private static int toolCallingAndRehydration(JobDispatcher dispatcher, TextStore store, ModelSpec spec, String transport, String today) {
        PinnedPicker.pinned = spec;
        int failures = 0;
        String conversationId = null;
        // 1. Tool calling through the dispatcher
        try {
            ProbeChat first = new ProbeChat(Job.workflow(USER, "openai-probe-tool-call-" + transport));
            first.addMessage("What is the current time? Call get_current_time and reply with its isoTimestamp verbatim.");
            dispatcher.submit(first).get();
            conversationId = first.getConversationId();
            String answer = first.answer.toString();
            // The tool's own output, persisted as a tool-result turn, is the proof the tool ran; the
            // date in the answer is the proof its result was read back rather than guessed; a call id
            // of OpenAI's minting is the proof the call came through the native channel
            String persisted = store.json.getOrDefault(conversationId, "");
            boolean toolRan = persisted.contains("isoTimestamp") && persisted.contains(today);
            boolean nativeCall = persisted.matches("(?s).*\"tool_use_id\"\\s*:\\s*\"call_.*");
            boolean pass = toolRan && nativeCall && answer.contains(today);
            failures += pass ? 0 : 1;
            log.info("[ChatProbe:{}] tool calling: {} -> \"{}\" (today {}, tool result persisted: {}, native call id: {}, {} save(s) of {})",
                    transport, pass ? "PASS" : "FAIL", answer.replace('\n', ' '), today, toolRan, nativeCall,
                    store.saves.getOrDefault(conversationId, 0), conversationId);
        }
        catch (Exception e) {
            failures++;
            log.error("[ChatProbe:{}] tool calling: FAIL {}", transport, chain(e));
        }
        // 2. Rehydration from JSON text through the service
        if (conversationId != null && store.exists(conversationId, USER)) {
            try {
                // Out of memory first, so the resume can only be satisfied from the stored text
                ConversationService.getInstance().evictConversation(conversationId);
                ProbeChat resumed = new ProbeChat(Job.workflow(USER, "openai-probe-resume-" + transport));
                resumed.setConversationId(conversationId);
                resumed.addMessage("Without calling any tool: what isoTimestamp did get_current_time return earlier in this"
                        + " conversation? Reply with that value only.");
                dispatcher.submit(resumed).get();
                String answer = resumed.answer.toString();
                boolean pass = answer.contains(today);
                failures += pass ? 0 : 1;
                log.info("[ChatProbe:{}] rehydration: {} -> \"{}\" (resumed {} from {} chars of JSON)", transport, pass ? "PASS" : "FAIL",
                        answer.replace('\n', ' '), conversationId, store.json.get(conversationId).length());
                if (!pass) {
                    // The whole persisted conversation, corrections included, is the evidence of what the model saw
                    log.info("[ChatProbe:{}] persisted conversation after the failed resume:\n{}", transport,
                            NucleoJsonSerializer.readTree(store.json.get(conversationId)).toPrettyString());
                }
            }
            catch (Exception e) {
                failures++;
                log.error("[ChatProbe:{}] rehydration: FAIL {}", transport, chain(e));
            }
        }
        else {
            failures++;
            log.error("[ChatProbe:{}] rehydration: FAIL nothing was persisted for {}", transport, conversationId);
        }
        return failures;
    }

    private static ConversationContext conversation(ModelSpec spec) {
        ConversationContext conversation = new ConversationContext();
        conversation.setModelBinding(ModelBinding.preResolved(spec));
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(OutputSize.COMPACT));
        return conversation;
    }

    private static void ask(ConversationContext conversation, String question, int outputTokens) {
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole("user");
        message.setTimestamp(Instant.now());
        message.addText(question);
        message.setRequestedOutputTokens(outputTokens);
        conversation.getMessages().add(message);
    }

    private static String chain(Throwable e) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            chain.append(t.getClass().getSimpleName()).append(": ").append(String.valueOf(t.getMessage()).replace('\n', ' ')).append(" <- ");
        }
        return chain.toString();
    }

    /** A 1x1 red PNG. */
    private static final String ONE_RED_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";
}
