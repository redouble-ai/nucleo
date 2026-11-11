/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.openai;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.tokens.*;
import org.slf4j.*;

import java.io.*;
import java.time.*;
import java.util.*;

/**
 * Manual live probe of prompt caching on an OpenAI model that takes cache breakpoints: what
 * the provider reads from its cache, writes to it and bills in full, for each
 * {@link Cacheability} a message can state, on both dialects. Not a unit test - it costs
 * tokens and needs a key - so it is a {@code main} that never runs in the suite.
 * {@link OpenAICacheBreakpointTest} pins where the client puts its breakpoints; this probe
 * proves the provider does with them what the placement is for.
 *
 * <p>Every scenario runs one conversation over several calls and checks the usage the
 * provider reports. The figure that matters is the chain: a call reads what the call before
 * it left stored, which is that call's cache read plus its cache write. Each scenario's
 * system content names two people drawn at random, so its cache starts cold whatever ran
 * before.
 *
 * <p>An OpenAI entry runs on both APIs, with the key from the {@code OPENAI_API_KEY}
 * environment variable via {@link OpenAISDKClient#setApiKey}, or else from the configured
 * secret store. An Azure AI Foundry entry runs on the Responses API, the one its client
 * speaks, with the resource and key from the configured secret store. The arguments are
 * catalog ids of entries with {@code cache_breakpoints}; every OpenAI and Foundry entry of
 * the catalog that has them when none is given.
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-11)
 */
public final class LiveOpenAICacheProbe {
    private static final Logger log = LoggerFactory.getLogger(LiveOpenAICacheProbe.class);

    /** Tokens a call may bill in full when the whole request is expected to be read or written: OpenAI counts cached tokens in steps of 128. */
    private static final int SLACK = 128;
    private static final String TOOL = "catalogue_lookup";
    private static final String OPENAI = new OpenAIProvider().key();
    private static final String FOUNDRY = new AzureFoundryOpenAIProvider().key();
    private static final String PIXEL = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    private final OpenAISDKClient client;
    private final ModelSpec spec;
    private final String label;
    private int passed;
    private int failed;

    private LiveOpenAICacheProbe(ModelSpec spec, OpenAISDKClient client, String label) {
        this.spec = spec;
        this.label = label;
        this.client = client;
        this.client.setModel(spec);
    }

    /** One probe per client that serves the entry: OpenAI on both APIs, Azure AI Foundry on the one it has. */
    private static List<LiveOpenAICacheProbe> probes(ModelSpec spec) {
        List<LiveOpenAICacheProbe> probes = new ArrayList<>();
        if (FOUNDRY.equals(spec.getProviderKey())) {
            probes.add(new LiveOpenAICacheProbe(spec, new AzureFoundryOpenAIClient(), spec.getId() + " on Azure AI Foundry"));
            return probes;
        }
        for (WireApi wireApi : WireApi.values()) {
            OpenAISDKClient client = new OpenAISDKClient(wireApi);
            String envKey = System.getenv("OPENAI_API_KEY");
            if (envKey != null && !envKey.isEmpty()) {
                client.setApiKey(envKey);
            }
            probes.add(new LiveOpenAICacheProbe(spec, client, spec.getId() + " " + wireApi));
        }
        return probes;
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

    public static void main(String[] args) {
        ModelsBackend catalog = new JsonModelsBackend();
        List<ModelSpec> specs = new ArrayList<>();
        for (String id : args) {
            ModelSpec spec = catalog.spec(id);
            if (spec.getCacheBreakpoints() == 0) {
                throw new IllegalArgumentException(id + " states no cache_breakpoints: it caches by itself, and every check here is of a breakpoint");
            }
            specs.add(spec);
        }
        if (specs.isEmpty()) {
            for (ModelSpec spec : catalog.all()) {
                if ((OPENAI.equals(spec.getProviderKey()) || FOUNDRY.equals(spec.getProviderKey())) && spec.getCacheBreakpoints() > 0) {
                    specs.add(spec);
                }
            }
        }
        int failures = 0;
        for (ModelSpec spec : specs) {
            for (LiveOpenAICacheProbe probe : probes(spec)) {
                probe.runAll();
                log.info("[CacheProbe] {} TOTAL passed={} failed={}", probe.label, probe.passed, probe.failed);
                failures += probe.failed;
            }
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private void runAll() {
        run("growing conversation", () -> growing(false));
        run("growing conversation, streamed", () -> growing(true));
        run("conversation that does not cache its turns", this::turnsOff);
        run("PREFERRED in a conversation that does not cache its turns", () -> statedByName(Cacheability.PREFERRED));
        run("ALWAYS in a conversation that does not cache its turns", () -> statedByName(Cacheability.ALWAYS));
        run("NEVER", this::never);
        run("UNLIKELY last turn", this::unlikelyLast);
        run("more ALWAYS than the provider writes per request", this::manyAlways);
        run("tool loop, the results turn last", this::toolLoop);
        run("breakpoint on an image", this::image);
        run("prefix below the provider's minimum", this::belowMinimum);
    }

    private interface Scenario {
        void run() throws Exception;
    }

    private void run(String name, Scenario scenario) {
        log.info("[CacheProbe] {} === {}", label, name);
        try {
            scenario.run();
        }
        catch (Exception e) {  // noqa - probe: a scenario that throws is a failed check, and the rest still run
            StringBuilder chain = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) {
                chain.append(t.getClass().getSimpleName()).append(": ").append(String.valueOf(t.getMessage()).replace('\n', ' ')).append(" <- ");
            }
            check(name + ": ran to its end", false, chain);
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

    /** Every call reads what the one before it left stored, and bills only the steps' remainder in full. */
    private void growing(boolean streamed) throws Exception {
        ConversationContext conversation = conversation(true, 3000);
        Call previous = null;
        for (int i = 1; i <= 5; i++) {
            conversation.getMessages().add(turn("passage " + i, 400));
            Call call = streamed ? stream(conversation) : send(conversation);
            if (previous == null) {
                check("call 1 reads nothing and writes the whole request", call.read == 0 && call.written > 0 && call.full <= SLACK, call);
            }
            else {
                check("call " + i + " reads what call " + (i - 1) + " left stored", Math.abs(call.read - previous.stored()) <= SLACK, call + " previous stored=" + previous.stored());
                check("call " + i + " writes what was added and bills nothing else in full", call.written > 0 && call.full <= SLACK, call);
            }
            previous = call;
        }
    }

    /** With turn caching off only the system content is stored: each call reads it and writes nothing more. */
    private void turnsOff() throws Exception {
        ConversationContext conversation = conversation(false, 3000);
        conversation.getMessages().add(turn("passage 1", 400));
        Call first = send(conversation);
        check("call 1 writes the system content and bills the turn in full", first.written > 0 && first.full > SLACK, first);
        for (int i = 2; i <= 3; i++) {
            conversation.getMessages().add(turn("passage " + i, 400));
            Call call = send(conversation);
            check("call " + i + " reads the system content and writes nothing", Math.abs(call.read - first.stored()) <= SLACK && call.written == 0, call + " system stored=" + first.stored());
        }
    }

    /** One turn named in a conversation that does not cache its turns is stored through, and the next call reads through it. */
    private void statedByName(Cacheability named) throws Exception {
        ConversationContext conversation = conversation(false, 3000);
        OutgoingMessage<String> stated = turn("passage 1", 400);
        stated.setCacheability(named);
        conversation.getMessages().add(stated);
        Call first = send(conversation);
        check("the call writes through the named turn", first.written > 0 && first.full <= SLACK, first);
        conversation.getMessages().add(turn("passage 2", 400));
        Call second = send(conversation);
        check("the next call reads through the named turn and writes nothing", Math.abs(second.read - first.stored()) <= SLACK && second.written == 0, second + " first stored=" + first.stored());
    }

    /** Nothing from a NEVER turn on is ever written or read, on any later call. */
    private void never() throws Exception {
        ConversationContext conversation = conversation(true, 3000);
        conversation.getMessages().add(turn("passage 1", 400));
        Call first = send(conversation);
        OutgoingMessage<String> secret = turn("passage 2", 400);
        secret.setCacheability(Cacheability.NEVER);
        conversation.getMessages().add(secret);
        Call second = send(conversation);
        check("the call that adds the NEVER turn writes nothing", second.written == 0, second);
        check("and reads no further than the turn before it", Math.abs(second.read - first.stored()) <= SLACK, second + " stored before it=" + first.stored());
        for (int i = 3; i <= 4; i++) {
            conversation.getMessages().add(turn("passage " + i, 400));
            Call call = send(conversation);
            check("call " + i + " still writes nothing and reads no further", call.written == 0 && Math.abs(call.read - first.stored()) <= SLACK, call + " stored before the NEVER turn=" + first.stored());
        }
    }

    /** An UNLIKELY last turn is sent without being stored; the end stays on the turn before it. */
    private void unlikelyLast() throws Exception {
        ConversationContext conversation = conversation(true, 3000);
        conversation.getMessages().add(turn("passage 1", 400));
        Call first = send(conversation);
        OutgoingMessage<String> passing = turn("passage 2", 400);
        passing.setCacheability(Cacheability.UNLIKELY);
        conversation.getMessages().add(passing);
        Call second = send(conversation);
        check("the UNLIKELY turn is billed in full and not written", second.written == 0 && second.full > SLACK, second);
        check("what was stored before it is read", Math.abs(second.read - first.stored()) <= SLACK, second + " first stored=" + first.stored());
    }

    /** Six ALWAYS turns are all marked; the provider's answer to more breakpoints than it writes per request is recorded as it comes. */
    private void manyAlways() throws Exception {
        ConversationContext conversation = conversation(false, 3000);
        for (int i = 1; i <= 6; i++) {
            OutgoingMessage<String> stated = turn("passage " + i, 300);
            stated.setCacheability(Cacheability.ALWAYS);
            conversation.getMessages().add(stated);
        }
        Call first = send(conversation);
        check("the provider accepts six marked turns and writes", first.written > 0, first);
        conversation.getMessages().add(turn("passage 7", 300));
        Call second = send(conversation);
        check("the next call reads what the first stored", second.read == first.stored(), second + " first stored=" + first.stored());
    }

    /** A tool loop, the results turn last on every call: each call reads what the previous one stored. */
    private void toolLoop() throws Exception {
        ConversationContext conversation = conversation(true, 3000);
        conversation.addTool(new ToolDefinitionBlock(TOOL, "Looks a title up in the catalogue", "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"}},\"required\":[\"key\"]}"));
        conversation.getMessages().add(turn("the question", 400));
        Call previous = send(conversation);
        for (int i = 1; i <= 3; i++) {
            IncomingMessage<String> call = new IncomingMessage<>(StringResponseHandler.instance);
            call.setTimestamp(Instant.now());
            call.setContentBlocks(List.of(new ToolUseBlock("call_cache_" + i, TOOL, "{\"key\":\"k\"}")));
            conversation.getMessages().add(call);
            OutgoingMessage<String> results = client.createOutgoingMessage(StringResponseHandler.instance);
            results.setRole("user");
            results.setTimestamp(Instant.now());
            results.addToolResult("call_cache_" + i, "{\"value\":\"" + prose("result " + i, 300) + "\"}", false);
            conversation.getMessages().add(results);
            Call next = send(conversation);
            check("iteration " + i + " reads what the call before it left stored", Math.abs(next.read - previous.stored()) <= SLACK, next + " previous stored=" + previous.stored());
            check("iteration " + i + " bills nothing else in full", next.full <= SLACK, next);
            previous = next;
        }
    }

    /** A turn that ends in an image carries its breakpoint on the image, and the provider stores through it. */
    private void image() throws Exception {
        ConversationContext conversation = conversation(true, 3000);
        OutgoingMessage<String> picture = client.createOutgoingMessage(StringResponseHandler.instance);
        picture.setRole("user");
        picture.setTimestamp(Instant.now());
        picture.addText("Passage with a picture. " + prose("the picture", 400) + " Please thank the reader in one short sentence.");
        picture.addImage(new ByteArrayInputStream(Base64.getDecoder().decode(PIXEL)), "image/png", "one pixel");
        conversation.getMessages().add(picture);
        Call first = send(conversation);
        check("the provider accepts a breakpoint on an image and writes through it", first.written > 0, first);
        conversation.getMessages().add(turn("passage 2", 400));
        Call second = send(conversation);
        check("the next call reads through the image", Math.abs(second.read - first.stored()) <= SLACK, second + " first stored=" + first.stored());
    }

    /** A breakpoint on a prefix under the provider's minimum is accepted, and caching begins once the conversation passes it. */
    private void belowMinimum() throws Exception {
        ConversationContext conversation = conversation(true, 20);
        conversation.getMessages().add(turn("passage 1", 20));
        Call previous = send(conversation);
        check("a request under the minimum is accepted and nothing is stored", previous.read == 0 && previous.written == 0, previous);
        boolean chained = false;
        for (int i = 2; i <= 7 && !chained; i++) {
            conversation.getMessages().add(turn("passage " + i, 700));
            Call call = send(conversation);
            if (previous.stored() > 0) {
                check("once something is stored the next call reads it", Math.abs(call.read - previous.stored()) <= SLACK, call + " previous stored=" + previous.stored());
                chained = true;
            }
            previous = call;
        }
        check("the conversation passed the minimum within seven calls", chained, previous);
    }

    // ---------------------------------------------------------------- plumbing

    private ConversationContext conversation(boolean cacheTurns, int objectiveWords) {
        ConversationContext conversation = TestModels.conversation(spec);
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(OutputSize.STANDARD));
        conversation.setCacheTurns(cacheTurns);
        // The time and a number make the system content new on every run, so no run reads what an earlier one stored
        conversation.putMainObjective("task", "You help the readers of a town library. Each message brings a passage from the library's history. Reply to each with one short sentence that thanks the reader. Today the desk is kept by " + someone() + " and " + someone() + ". " + prose("the library's history", objectiveWords));
        return conversation;
    }

    private OutgoingMessage<String> turn(String label, int words) {
        OutgoingMessage<String> message = client.createOutgoingMessage(StringResponseHandler.instance);
        message.setRole("user");
        message.setTimestamp(Instant.now());
        message.addText("Passage for " + label + ", brought in by " + someone() + ". " + prose(label, words) + " Please thank the reader in one short sentence.");
        return message;
    }

    private static String prose(String word, int words) {
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
     * those is refused by the safety classifiers of some models.
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
        "The garden behind the building has an apple tree that was planted the year the roof was mended."
    };

    private Call send(ConversationContext conversation) throws Exception {
        return usage(client.singleResponse(new LLMRequest<>(conversation)));
    }

    private Call stream(ConversationContext conversation) throws Exception {
        return usage(client.streamResponse(new LLMRequest<>(conversation), chunk -> { }));
    }

    private Call usage(LLMResponse<?> response) {
        if (!response.isSuccessful()) {
            throw new IllegalStateException("the call did not succeed: " + response);
        }
        TokenLedger usage = response.getUsage();
        int read = usage.cacheReadTokens() == null ? 0 : usage.cacheReadTokens();
        Call call = new Call(usage.inputTokens(), read, usage.cacheWriteTokens(), usage.inputTokens() - read - usage.cacheWriteTokens());
        log.info("[CacheProbe]     {}", call);
        return call;
    }
}
