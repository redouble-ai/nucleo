/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock.anthropic;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.harness.tokens.*;
import com.fasterxml.jackson.databind.*;
import org.slf4j.*;

import java.io.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;

/**
 * Manual live probe of prompt caching on Claude through Amazon Bedrock: what the provider
 * reads from its cache, writes to it and bills in full, for each {@link Cacheability} a
 * message can state. Not a unit test - it costs tokens and needs AWS credentials - so it is a
 * {@code main} that never runs in the suite. The request-shape tests pin where the client
 * puts its breakpoints; this probe proves the provider does with them what the placement is
 * for.
 *
 * <p>Every scenario runs one conversation over several calls and checks the usage the
 * provider reports. The figure that matters is the chain: a call reads exactly what the
 * call before it left stored, which is that call's cache read plus its cache write. Each
 * scenario's system content names two people drawn at random, so its cache starts cold
 * whatever ran before.
 *
 * <p>Run with the test classpath, credentials and region from AWS's own resolution:
 *
 * <pre>
 * java -cp "target/classes:target/test-classes:$(cat cp.txt)" ai.redouble.nucleo.providers.bedrock.anthropic.LiveCacheProbe [catalog id ...]
 * </pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-11)
 */
public final class LiveCacheProbe {
    private static final Logger log = LoggerFactory.getLogger(LiveCacheProbe.class);

    /** Tokens a call may bill in full when the whole request is expected to be read or written. */
    private static final int SLACK = 8;
    private static final String TOOL = "cabinet_lookup";
    private static final String PIXEL = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    private final AnthropicBedrockSDKClient client;
    private final ModelSpec spec;
    private int passed;
    private int failed;
    private int refusals;
    /** Whether the latest call was refused by a safety classifier. */
    private boolean refused;

    private LiveCacheProbe(ModelSpec spec) {
        this.spec = spec;
        this.client = new AnthropicBedrockSDKClient();
        this.client.setModel(spec);
    }

    /** What the provider reported for one call. */
    private record Call(int input, int read, int written, int full) {
        int stored() {
            return read + written;
        }

        @Override
        public String toString() {
            return "input=" + input + " read=" + read + " written=" + written + " full=" + full;
        }
    }

    /** Args: catalog ids of the entries to probe; the two Bedrock Claude entries of the seed catalog when none is given. */
    public static void main(String[] args) {
        String[] ids = args.length > 0 ? args : new String[]{"claude-sonnet-5-bedrock", "claude-opus-5-bedrock"};
        ModelsBackend catalog = new JsonModelsBackend();
        int failures = 0;
        for (String id : ids) {
            LiveCacheProbe probe = new LiveCacheProbe(catalog.spec(id));
            probe.runAll();
            log.info("[CacheProbe] {} TOTAL passed={} failed={} refused={}", id, probe.passed, probe.failed, probe.refusals);
            failures += probe.failed;
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private void runAll() {
        run("growing conversation", this::growing);
        run("growing conversation, streamed", this::growingStreamed);
        run("conversation that does not cache its turns", this::turnsOff);
        run("PREFERRED in a conversation that does not cache its turns", () -> statedByName(Cacheability.PREFERRED));
        run("ALWAYS in a conversation that does not cache its turns", () -> statedByName(Cacheability.ALWAYS));
        run("NEVER", this::never);
        run("UNLIKELY last turn", this::unlikelyLast);
        run("more ALWAYS than the provider accepts", this::tooManyAlways);
        run("as many ALWAYS as the provider accepts", this::allowedAlways);
        run("tool loop, the results turn last", this::toolLoop);
        run("breakpoint on a tool call", () -> carrier("tool call"));
        run("breakpoint on a tool result", () -> carrier("tool result"));
        run("breakpoint on an image", () -> carrier("image"));
        run("breakpoint on a document", () -> carrier("document"));
        run("compaction that keeps the first turn", () -> compaction(true));
        run("compaction that replaces the first turn", () -> compaction(false));
        run("prefix below the provider's minimum", this::belowMinimum);
        run("stored conversation with the old boolean on every message", this::storedBoolean);
        run("growing conversation with thinking", this::growingWithThinking);
        run("tool loop the model drives, with thinking", this::modelDrivenToolLoop);
    }

    private interface Scenario {
        void run() throws Exception;
    }

    private void run(String name, Scenario scenario) {
        log.info("[CacheProbe] {} === {}", spec.getId(), name);
        try {
            scenario.run();
        }
        catch (Exception e) {  // noqa - probe: a scenario that throws is a failed check, and the rest still run
            check(name + ": ran to its end", false, e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).replace('\n', ' '));
        }
    }

    private void check(String what, boolean holds, Object evidence) {
        if (holds) {
            passed++;
            log.info("[CacheProbe]   PASS {} [{}]", what, evidence);
        }
        else {
            failed++;
            log.error("[CacheProbe]   FAIL {} [{}]", what, evidence);
        }
    }

    // ---------------------------------------------------------------- scenarios

    /** Each call reads all the call before it sent and writes only what was added; nothing is billed in full. */
    private void growing() throws Exception {
        ConversationContext conversation = conversation(true, Depth.IMMEDIATE, 3000);
        chain(conversation, 5, false);
    }

    private void growingStreamed() throws Exception {
        ConversationContext conversation = conversation(true, Depth.IMMEDIATE, 3000);
        chain(conversation, 3, true);
    }

    private void growingWithThinking() throws Exception {
        ConversationContext conversation = conversation(true, Depth.THOROUGH, 3000);
        chain(conversation, 3, false);
    }

    /** Runs {@code calls} calls, each adding one turn, and checks the chain on every one. */
    private void chain(ConversationContext conversation, int calls, boolean streamed) throws Exception {
        Call previous = null;
        for (int i = 1; i <= calls; i++) {
            conversation.getMessages().add(turn("turn " + i, 400));
            Call call = streamed ? stream(conversation) : send(conversation);
            if (previous == null) {
                check("call 1 reads nothing and writes the whole request", call.read == 0 && call.written > 0 && call.full <= SLACK, call);
            }
            else {
                check("call " + i + " reads what call " + (i - 1) + " left stored", call.read == previous.stored(), call + " previous stored=" + previous.stored());
                check("call " + i + " writes what was added and bills nothing in full", call.written > 0 && call.full <= SLACK, call);
            }
            previous = call;
        }
    }

    /** Only the system content is stored; every turn is billed in full on every call. */
    private void turnsOff() throws Exception {
        ConversationContext conversation = conversation(false, Depth.IMMEDIATE, 3000);
        conversation.getMessages().add(turn("turn 1", 400));
        Call first = send(conversation);
        check("call 1 writes the system content and bills the turn in full", first.read == 0 && first.written > 0 && first.full > SLACK, first);
        Call previous = first;
        for (int i = 2; i <= 3; i++) {
            conversation.getMessages().add(turn("turn " + i, 400));
            Call call = send(conversation);
            check("call " + i + " reads the system content and writes nothing", call.read == first.written && call.written == 0, call);
            check("call " + i + " bills more in full than call " + (i - 1), call.full > previous.full, call + " previous full=" + previous.full);
            previous = call;
        }
    }

    /** A message that states ALWAYS or PREFERRED ends a stored prefix in a conversation that caches nothing else. */
    private void statedByName(Cacheability stated) throws Exception {
        ConversationContext conversation = conversation(false, Depth.IMMEDIATE, 3000);
        OutgoingMessage<String> named = turn("turn 1", 400);
        named.setCacheability(stated);
        conversation.getMessages().add(named);
        Call first = send(conversation);
        check("call 1 writes through the " + stated + " turn", first.written > 0 && first.full <= SLACK, first);
        conversation.getMessages().add(turn("turn 2", 400));
        Call second = send(conversation);
        check("call 2 reads through the " + stated + " turn and writes nothing", second.read == first.stored() && second.written == 0 && second.full > SLACK, second + " first stored=" + first.stored());
    }

    /** Nothing at or after a NEVER turn is written, however long the conversation goes on. */
    private void never() throws Exception {
        ConversationContext conversation = conversation(true, Depth.IMMEDIATE, 3000);
        conversation.getMessages().add(turn("turn 1", 400));
        Call first = send(conversation);
        OutgoingMessage<String> secret = turn("turn 2", 400);
        secret.setCacheability(Cacheability.NEVER);
        conversation.getMessages().add(secret);
        Call second = send(conversation);
        check("the call that adds the NEVER turn bills it in full", second.read == first.stored() && second.full > SLACK, second + " first stored=" + first.stored());
        int storedBeforeNever = second.stored();
        Call previous = second;
        for (int i = 3; i <= 4; i++) {
            conversation.getMessages().add(turn("turn " + i, 400));
            Call call = send(conversation);
            check("call " + i + " reads only what precedes the NEVER turn and writes nothing", call.read == storedBeforeNever && call.written == 0, call + " stored before NEVER=" + storedBeforeNever);
            check("call " + i + " bills the NEVER turn and all after it in full", call.full > previous.full, call + " previous full=" + previous.full);
            previous = call;
        }
    }

    /** An UNLIKELY turn does not end a stored prefix while it is last, and rides inside one afterwards. */
    private void unlikelyLast() throws Exception {
        ConversationContext conversation = conversation(true, Depth.IMMEDIATE, 3000);
        conversation.getMessages().add(turn("turn 1", 400));
        Call first = send(conversation);
        OutgoingMessage<String> volatileTurn = turn("turn 2", 400);
        volatileTurn.setCacheability(Cacheability.UNLIKELY);
        conversation.getMessages().add(volatileTurn);
        Call second = send(conversation);
        check("while it is last the UNLIKELY turn is billed in full", second.read == first.stored() && second.full > SLACK, second + " first stored=" + first.stored());
        conversation.getMessages().add(turn("turn 3", 400));
        Call third = send(conversation);
        check("the next call stores it inside the prefix that ends on the new last turn", third.read == second.stored() && third.written > 0 && third.full <= SLACK, third + " second stored=" + second.stored());
        conversation.getMessages().add(turn("turn 4", 400));
        Call fourth = send(conversation);
        check("and the call after reads it from the cache", fourth.read == third.stored() && fourth.full <= SLACK, fourth + " third stored=" + third.stored());
    }

    /** Five ALWAYS turns and the system content are six breakpoints; the provider refuses and the caller sees it. */
    private void tooManyAlways() {
        ConversationContext conversation = conversation(false, Depth.IMMEDIATE, 3000);
        for (int i = 1; i <= 5; i++) {
            OutgoingMessage<String> turn = turn("turn " + i, 100);
            turn.setCacheability(Cacheability.ALWAYS);
            conversation.getMessages().add(turn);
        }
        try {
            Call call = send(conversation);
            check("the provider refuses the request", false, "accepted: " + call);
        }
        catch (Exception e) {  // noqa - probe: the refusal is the expected outcome
            check("the provider refuses the request and the refusal reaches the caller", true, e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).replace('\n', ' '));
        }
    }

    /** Three ALWAYS turns and the system content are four breakpoints, which the provider accepts. */
    private void allowedAlways() throws Exception {
        ConversationContext conversation = conversation(false, Depth.IMMEDIATE, 3000);
        for (int i = 1; i <= 3; i++) {
            OutgoingMessage<String> turn = turn("turn " + i, 400);
            turn.setCacheability(Cacheability.ALWAYS);
            conversation.getMessages().add(turn);
        }
        Call call = send(conversation);
        check("the request is accepted and written through the last ALWAYS turn", call.written > 0 && call.full <= SLACK, call);
    }

    /**
     * The shape of a thinker's loop: every call ends on a turn of tool results, with the
     * handler's reply instructions after them. The history is written by hand, so no call depends
     * on the model choosing to call the tool.
     */
    private void toolLoop() throws Exception {
        ConversationContext conversation = conversation(true, Depth.IMMEDIATE, 3000);
        conversation.addTool(new ToolDefinitionBlock(TOOL, "Looks a value up", "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"}},\"required\":[\"key\"]}"));
        conversation.getMessages().add(turn("the question", 400));
        Call previous = null;
        for (int i = 1; i <= 4; i++) {
            conversation.getMessages().add(toolCall("toolu_probe_" + i));
            OutgoingMessage<String> results = client.createOutgoingMessage(StringResponseHandler.instance);
            results.setRole("user");
            results.setTimestamp(Instant.now());
            results.addToolResult("toolu_probe_" + i, "{\"value\":\"" + filler("result " + i, 300) + "\"}", false);
            conversation.getMessages().add(results);
            Call call = send(conversation);
            conversation.getMessages().removeLast();
            if (previous != null) {
                check("iteration " + i + " reads what iteration " + (i - 1) + " left stored", call.read == previous.stored(), call + " previous stored=" + previous.stored());
            }
            check("iteration " + i + " bills nothing in full", call.written > 0 && call.full <= SLACK, call);
            previous = call;
        }
    }

    /** A turn that is one block of the named kind and nothing else, stating ALWAYS, ends a stored prefix. */
    private void carrier(String kind) throws Exception {
        ConversationContext conversation = conversation(false, Depth.IMMEDIATE, 3000);
        conversation.addTool(new ToolDefinitionBlock(TOOL, "Looks a value up", "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"}},\"required\":[\"key\"]}"));
        conversation.getMessages().add(turn("the question", 400));
        switch (kind) {
        case "tool call" -> {
            IncomingMessage<String> call = toolCall("toolu_probe_1");
            call.setCacheability(Cacheability.ALWAYS);
            conversation.getMessages().add(call);
            conversation.getMessages().add(bareResults("toolu_probe_1"));
        }
        case "tool result" -> {
            conversation.getMessages().add(toolCall("toolu_probe_1"));
            OutgoingMessage<String> results = bareResults("toolu_probe_1");
            results.setCacheability(Cacheability.ALWAYS);
            conversation.getMessages().add(results);
        }
        case "image" -> {
            OutgoingMessage<String> image = new OutgoingMessage<>(null);
            image.setRole("user");
            image.addImage(new ByteArrayInputStream(Base64.getDecoder().decode(PIXEL)), "image/png", "one pixel");
            image.setCacheability(Cacheability.ALWAYS);
            conversation.getMessages().add(image);
        }
        default -> {
            OutgoingMessage<String> document = new OutgoingMessage<>(null);
            document.setRole("user");
            document.addFile(new ByteArrayInputStream(pdf("Office memo. " + filler("page", 200))), "application/pdf", "memo.pdf");
            document.setCacheability(Cacheability.ALWAYS);
            conversation.getMessages().add(document);
        }
        }
        conversation.getMessages().add(turn("after the " + kind, 400));
        Call first = send(conversation);
        check("the provider accepts a breakpoint on a " + kind + " and writes through it", first.written > 0 && first.full > SLACK, first);
        conversation.getMessages().add(turn("one more", 400));
        Call second = send(conversation);
        check("the next call reads through the " + kind + " and writes nothing", second.read == first.stored() && second.written == 0, second + " first stored=" + first.stored());
    }

    /**
     * A compaction rewrites the turns after the first one. The call after it reads through
     * the first turn when that turn was kept, the system content alone when it was not, and
     * the calls after that chain again.
     */
    private void compaction(boolean keepFirstTurn) throws Exception {
        ConversationContext conversation = conversation(true, Depth.IMMEDIATE, 3000);
        conversation.getMessages().add(turn("turn 1", 400));
        Call first = send(conversation);
        Call previous = first;
        for (int i = 2; i <= 4; i++) {
            conversation.getMessages().add(turn("turn " + i, 400));
            previous = send(conversation);
        }
        Message original = conversation.getMessages().getFirst();
        conversation.getMessages().clear();
        if (keepFirstTurn) {
            conversation.getMessages().add(original);
        }
        conversation.getMessages().add(turn("the earlier batches in summary", 300));
        conversation.getMessages().add(turn("turn 5", 400));
        Call after = send(conversation);
        if (keepFirstTurn) {
            check("the call after the compaction reads through the first turn", after.read == first.stored(), after + " stored through the first turn=" + first.stored());
        }
        else {
            check("the call after the compaction reads the system content alone", after.read > 0 && after.read < first.stored(), after + " stored through the first turn=" + first.stored());
        }
        check("it reads less than the call before the compaction left stored", after.read < previous.stored(), after + " stored before=" + previous.stored());
        check("it writes the compacted conversation and bills nothing in full", after.written > 0 && after.full <= SLACK, after);
        conversation.getMessages().add(turn("turn 6", 400));
        Call next = send(conversation);
        check("the call after that reads all of it", next.read == after.stored() && next.full <= SLACK, next + " stored=" + after.stored());
    }

    /** A breakpoint on a prefix under the provider's minimum is ignored, and caching begins once the conversation passes it. */
    private void belowMinimum() throws Exception {
        ConversationContext conversation = conversation(true, Depth.IMMEDIATE, 20);
        conversation.getMessages().add(turn("turn 1", 20));
        Call first = send(conversation);
        check("a request under the minimum is accepted and nothing is stored", first.read == 0 && first.written == 0, first);
        Call previous = first;
        boolean chained = false;
        for (int i = 2; i <= 7 && !chained; i++) {
            conversation.getMessages().add(turn("turn " + i, 700));
            Call call = send(conversation);
            if (previous.stored() > 0) {
                check("once something is stored the next call reads it", call.read == previous.stored() && call.full <= SLACK, call + " previous stored=" + previous.stored());
                chained = true;
            }
            previous = call;
        }
        check("the conversation passed the minimum within seven calls", chained, previous);
    }

    /**
     * A conversation stored before {@code Cacheability} existed, with the boolean set on all
     * six of its messages, is restored and sent. Six breakpoints would be refused.
     */
    private void storedBoolean() throws Exception {
        ConversationContext conversation = conversation(true, Depth.IMMEDIATE, 3000);
        for (int i = 1; i <= 6; i++) {
            conversation.getMessages().add(turn("turn " + i, 300));
        }
        ConversationPersistenceSnapshot snapshot = conversation.toSnapshot();
        for (MessageSnapshot message : snapshot.getMessages()) {
            message.setEnableCache(true);
        }
        ConversationContext restored = snapshot.toConversation(StringResponseHandler.instance);
        restored.setModelBinding(ModelBinding.preResolved(spec));
        restored.setDepth(Depth.IMMEDIATE);
        restored.setOutputDeclaration(OutputDeclaration.of(OutputSize.STANDARD));
        restored.setCacheTurns(true);
        check("every restored message states PREFERRED", restored.getMessages().stream().allMatch(message -> message.getCacheability() == Cacheability.PREFERRED), restored.getMessages().size() + " messages");
        Call first = send(restored);
        check("the provider accepts the restored conversation", first.written > 0 && first.full <= SLACK, first);
        restored.getMessages().add(turn("turn 7", 300));
        Call second = send(restored);
        check("and the next call reads what was stored", second.read == first.stored() && second.full <= SLACK, second + " first stored=" + first.stored());
    }

    /**
     * A loop the model drives: it is asked to call the tool, its own reply (thinking blocks
     * and the tool call) goes back with the result, three times over.
     */
    private void modelDrivenToolLoop() throws Exception {
        ConversationContext conversation = conversation(true, Depth.STANDARD, 3000);
        conversation.addTool(new ToolDefinitionBlock(TOOL, "Looks a value up by key", "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"}},\"required\":[\"key\"]}"));
        OutgoingMessage<String> ask = turn("the question", 400);
        ask.addText("Call the tool " + TOOL + " with key \"k1\" now. Do not answer in text.");
        conversation.getMessages().add(ask);
        Call previous = sendForTheReply(conversation);
        for (int i = 1; i <= 3; i++) {
            if (refused) {
                log.info("[CacheProbe]     the loop ends before iteration {}: the call before it was refused and left no reply", i);
                return;
            }
            String toolUseId = null;
            IncomingMessage<?> reply = (IncomingMessage<?>) conversation.getMessages().getLast();
            if (reply.getContentBlocks() != null) {
                for (ContentBlock block : reply.getContentBlocks()) {
                    if (block instanceof ToolUseBlock use) {
                        toolUseId = use.toolUseId();
                    }
                }
            }
            if (toolUseId == null) {
                check("the model called the tool on iteration " + i, false, reply.getRawContent());
                return;
            }
            OutgoingMessage<String> results = client.createOutgoingMessage(StringResponseHandler.instance);
            results.setRole("user");
            results.setTimestamp(Instant.now());
            results.addToolResult(toolUseId, "{\"value\":\"" + filler("result " + i, 300) + "\"}", false);
            results.addText(i < 3 ? "Call the tool " + TOOL + " again with key \"k" + (i + 1) + "\". Do not answer in text." : "Please thank the reader in one short sentence.");
            conversation.getMessages().add(results);
            Call call = sendForTheReply(conversation);
            check("iteration " + i + " reads what the call before it left stored", call.read == previous.stored(), call + " previous stored=" + previous.stored());
            check("iteration " + i + " bills nothing in full", call.full <= SLACK, call);
            previous = call;
        }
    }

    // ---------------------------------------------------------------- plumbing

    private ConversationContext conversation(boolean cacheTurns, Depth depth, int objectiveWords) {
        ConversationContext conversation = TestModels.conversation(spec);
        conversation.setDepth(depth);
        conversation.setOutputDeclaration(OutputDeclaration.of(OutputSize.STANDARD));
        conversation.setCacheTurns(cacheTurns);
        // The names make the system content new on every run, so no run reads what an earlier one stored
        conversation.putMainObjective("task", "You help the readers of a town library. Each message brings a passage from the library's history. Reply to each with one short sentence that thanks the reader. Today the desk is kept by " + someone() + " and " + someone() + ". " + filler("the library's history", objectiveWords));
        return conversation;
    }

    private OutgoingMessage<String> turn(String label, int words) {
        OutgoingMessage<String> message = client.createOutgoingMessage(StringResponseHandler.instance);
        message.setRole("user");
        message.setTimestamp(Instant.now());
        message.addText("Passage for " + label + ", brought in by " + someone() + ". " + filler(label, words) + " Please thank the reader in one short sentence.");
        return message;
    }

    private static IncomingMessage<String> toolCall(String toolUseId) {
        IncomingMessage<String> call = new IncomingMessage<>(StringResponseHandler.instance);
        call.setTimestamp(Instant.now());
        call.setContentBlocks(List.of(new ToolUseBlock(toolUseId, TOOL, "{\"key\":\"k\"}")));
        return call;
    }

    /** A results turn that is its tool result and nothing else: no handler, so no instructions are appended. */
    private static OutgoingMessage<String> bareResults(String toolUseId) {
        OutgoingMessage<String> results = new OutgoingMessage<>(null);
        results.setRole("user");
        results.setTimestamp(Instant.now());
        results.addToolResult(toolUseId, "{\"value\":\"" + filler("result", 300) + "\"}", false);
        return results;
    }

    private static String filler(String word, int words) {
        StringBuilder text = new StringBuilder("Reading for ").append(word).append(". ");
        for (int i = 0; i < words / 8 + 1; i++) {
            text.append(PROSE[i % PROSE.length]).append(' ');
        }
        return text.toString().trim();
    }

    private static final String[] FIRST_NAMES = {"Margaret", "Edwin", "Clara", "Walter", "Agnes", "Hugh", "Florence", "Arthur", "Edith", "Cecil", "Mabel", "Ernest", "Winifred", "Stanley", "Hilda", "Leonard", "Dorothy", "Percy", "Ethel", "Harold", "Beatrice", "Sidney", "Gladys", "Albert"};
    private static final String[] SURNAMES = {"Hollis", "Marlow", "Thorne", "Ashby", "Pryce", "Wetherby", "Calloway", "Fenwick", "Hargrove", "Linton", "Mossop", "Ormsby", "Penhale", "Quarmby", "Rudge", "Selwyn", "Tolland", "Underhay", "Varley", "Whitlock", "Yardley", "Aldous", "Birkett", "Cobbold"};
    private static final String[] TOWNS = {"Ashford", "Brindle", "Coldwell", "Dunmere", "Eastlake", "Fairholt", "Greywick", "Hartsop", "Ingleby", "Kelbrook", "Langdale", "Milnthorpe", "Netherby", "Oakham", "Pendle", "Ravenglass", "Stainmore", "Thirlby", "Ulpha", "Wensley", "Yarrow", "Arkholme", "Bowland", "Cartmel"};
    private static final Random RANDOM = new Random();

    /**
     * A person of the town, by name: what makes a request unlike any sent before it. Names
     * and not a UUID, a timestamp or a long number, because a long prompt that carries one of
     * those is refused by the safety classifiers of some models before any cache behaviour is
     * exercised.
     */
    private static String someone() {
        return FIRST_NAMES[RANDOM.nextInt(FIRST_NAMES.length)] + " " + SURNAMES[RANDOM.nextInt(SURNAMES.length)] + " of " + TOWNS[RANDOM.nextInt(TOWNS.length)];
    }

    /** Ordinary prose to fill a request with, on a harmless subject. */
    private static final String[] PROSE = {
        "The town library opened in the spring, in a brick building that had once been a grain store.",
        "Its first librarian kept the catalogue by hand, in green ledgers that are still on the top shelf.",
        "Children came after school to read by the tall windows that face the river.",
        "A reading room was added a few years later, paid for by the sale of the old mill.",
        "On market days the farmers left their baskets at the door and borrowed almanacs.",
        "The clock above the desk has run a few minutes slow for as long as anyone remembers.",
        "In winter the stove in the corner was lit before dawn so the ink would not freeze.",
        "A local carpenter built the oak shelves and carved a small owl into the end of each one.",
        "The collection grew slowly, a crate of books at a time, most of them gifts from the parish.",
        "Maps of the valley hang in the stairwell, each drawn by a different surveyor.",
        "Every autumn the librarian reads aloud on the steps, and the square fills with listeners.",
        "The garden behind the building has an apple tree that was planted the year the roof was mended.",
        "Visitors sign a guest book, and the oldest pages have faded to the colour of tea.",
        "A cat called Marble has lived among the atlases for nine years and answers to no one.",
        "The newspapers are bound each December and stored in the cellar, where it is cool and dry.",
        "Schoolteachers borrow the globe for geography lessons and return it polished.",
        "When the river flooded, neighbours carried the books upstairs in a single night.",
        "The bell at the door was taken from a fishing boat and still smells faintly of salt.",
        "A shelf near the entrance holds seed catalogues, which are the most borrowed items in March.",
        "The reading lamps were a gift from the railway company when the station opened.",
        "On the longest day of the year the library stays open until the light is gone.",
        "Its doors have been painted blue, then green, then blue again, by vote of the readers.",
        "A small plaque by the stairs names the families who mended the roof after the storm.",
        "The librarian keeps a jar of pencils on the desk and asks only that they be returned sharp."
    };

    private Call send(ConversationContext conversation) throws Exception {
        return call(conversation, false, true);
    }

    private Call stream(ConversationContext conversation) throws Exception {
        return call(conversation, true, true);
    }

    /** A call whose reply the scenario reads: after a refusal ({@link #refused}) there is none to go on with. */
    private Call sendForTheReply(ConversationContext conversation) throws Exception {
        return call(conversation, false, false);
    }

    /**
     * One call and its usage. The safety classifiers of some models refuse a share of these
     * harmless requests, and a request once refused is refused again however often it is
     * sent. A refused request was still read from the cache, and its response reports the
     * usage, so a refusal measures the cache as well as an answer does. With {@code standIn}
     * the conversation then goes on with a reply the probe writes itself.
     */
    private Call call(ConversationContext conversation, boolean streamed, boolean standIn) throws Exception {
        var request = new LLMRequest<>(conversation);
        try {
            Call call = usage(streamed ? client.streamResponse(request, chunk -> { }) : client.singleResponse(request));
            refused = false;
            return call;
        }
        catch (ProviderRefusalException e) {
            JsonNode usage = NucleoJsonSerializer.readTree(request.getOutputJson()).findParent("cache_creation_input_tokens");
            Call call = new Call(usage.path("input_tokens").asInt() + usage.path("cache_read_input_tokens").asInt() + usage.path("cache_creation_input_tokens").asInt(),
                    usage.path("cache_read_input_tokens").asInt(), usage.path("cache_creation_input_tokens").asInt(), usage.path("input_tokens").asInt());
            log.info("[CacheProbe]     refused ({}): {}", e.getCategory(), call);
            refusals++;
            refused = true;
            if (standIn) {
                IncomingMessage<String> reply = new IncomingMessage<>(StringResponseHandler.instance);
                reply.setTimestamp(Instant.now());
                reply.setContentBlocks(List.of(new TextBlock("Thank you for bringing this passage to the library.")));
                conversation.getMessages().add(reply);
            }
            return call;
        }
    }

    private Call usage(LLMResponse<?> response) {
        if (!response.isSuccessful()) {
            throw new IllegalStateException("the call did not succeed: " + response);
        }
        TokenLedger usage = response.getUsage();
        Call call = new Call(usage.inputTokens(), usage.cacheReadTokens(), usage.cacheWriteTokens(), usage.uncachedInputTokens());
        log.info("[CacheProbe]     {}", call);
        return call;
    }

    /** A one-page PDF holding the given text, with a correct cross-reference table. */
    private static byte[] pdf(String text) {
        String stream = "BT /F1 10 Tf 40 780 Td (" + text.replace("(", "").replace(")", "") + ") Tj ET";
        String[] objects = {
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        };
        StringBuilder body = new StringBuilder("%PDF-1.4\n");
        int[] offsets = new int[objects.length];
        for (int i = 0; i < objects.length; i++) {
            offsets[i] = body.length();
            body.append(i + 1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n");
        }
        int xref = body.length();
        body.append("xref\n0 ").append(objects.length + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) {
            body.append(String.format("%010d 00000 n \n", offset));
        }
        body.append("trailer\n<< /Size ").append(objects.length + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return body.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
