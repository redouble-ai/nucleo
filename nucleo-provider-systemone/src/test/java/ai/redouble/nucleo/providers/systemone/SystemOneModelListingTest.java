/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.systemone;

import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.models.discovery.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The endpoint's listing answers three questions: what the discovery lists, what stands
 * behind each served name on the status surface, and which wire ids the endpoint serves.
 * Pinned on a Kev listing as recorded from a running server, and on an endpoint that does not
 * list at all.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
class SystemOneModelListingTest {
    /** A Kev-9B server's listing on a Mac, as recorded; the two names alias one run. */
    static final String KEV = """
            {"models":[
              {"name":"kev-latest","description":"Kev pointer head on Qwen/Qwen3.5-9B-Base, serving jaredpalmer/kev-9b at temperature 2.30",
               "release_date":"2026-09-24","run":"jaredpalmer/kev-9b","base":"Qwen/Qwen3.5-9B-Base","lora":16,"device":"mps","backend":"mlx",
               "dtype":"bfloat16","temperature":2.2973967099940698,"cuda_graphs":null,
               "prefix_cache":{"size":4,"min_state_tokens":0,"hits":66,"misses":6,"cached_states":4},"batches":{"count":28,"requests":72,"queued":0}},
              {"name":"jev-latest","description":"Kev pointer head on Qwen/Qwen3.5-9B-Base, serving jaredpalmer/kev-9b at temperature 2.30",
               "run":"jaredpalmer/kev-9b","base":"Qwen/Qwen3.5-9B-Base","device":"mps","backend":"mlx","dtype":"bfloat16","temperature":2.2973967099940698}
            ]}""";
    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/kev" + SystemOneModelListing.MODELS_PATH, exchange -> answer(exchange, 200, KEV));
        server.createContext("/mute" + SystemOneModelListing.MODELS_PATH, exchange -> answer(exchange, 404, "{\"detail\":\"Not Found\"}"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
        SystemOneTestSecrets.host = null;
        SystemOneTestSecrets.localHost = null;
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void theListingIsParsedIntoServedNamesWithWhatStandsBehindThem() throws Exception {
        List<SystemOneModelListing.Served> served = SystemOneModelListing.parse(KEV);
        assertEquals(List.of("kev-latest", "jev-latest"), served.stream().map(SystemOneModelListing.Served::name).toList());
        SystemOneModelListing.Served kev = served.get(0);
        assertEquals("jaredpalmer/kev-9b", kev.run());
        assertEquals("jaredpalmer/kev-9b on mps (mlx, bfloat16), temperature 2.30", kev.facts());
        DiscoveredModel discovered = kev.discovered();
        assertEquals("kev-latest", discovered.wireModelId());
        assertTrue(discovered.note().contains("serving jaredpalmer/kev-9b"), "the endpoint's own words travel as the note: " + discovered.note());
        SystemOneModelListing.Served bare = SystemOneModelListing.parse("{\"models\":[{\"id\":\"jev-1.13.0\"}]}").get(0);
        assertEquals("jev-1.13.0", bare.name(), "an endpoint that lists ids alone still lists");
        assertEquals("jev-1.13.0", bare.facts(), "and says nothing beyond the name");
        assertEquals("jaredpalmer/kev-9b on mps, bfloat16", kev.summary(), "the line a person sees: model, device, precision");
        assertEquals("jev-1.13.0", bare.summary());
        IOException noArray = assertThrows(IOException.class, () -> SystemOneModelListing.parse("{\"data\":[]}"));
        assertTrue(noArray.getMessage().contains("'models'"), noArray.getMessage());
    }

    @Test
    void theProviderListsVerifiesFactsAndServesFromTheEndpointsListing() throws Exception {
        SystemOneTestSecrets.host = base + "/kev";
        SystemOneProvider provider = new SystemOneProvider();
        List<DiscoveredModel> listed = provider.listModels();
        assertEquals(List.of("kev-latest"), listed.stream().map(DiscoveredModel::wireModelId).toList(),
                "one run listed under two names is discovered once, under the first name");
        Map<String, String> facts = provider.connectionFacts();
        assertEquals(base + "/kev", facts.get("endpoint"));
        assertEquals("jaredpalmer/kev-9b on mps, bfloat16", facts.get("model"), "which model, on what device, at what precision");
        assertEquals(2, facts.size(), "one run is one line, whatever names it answers under: " + facts);
        assertTrue(provider.serves("kev-latest"));
        assertTrue(provider.serves("jev-latest"));
        assertFalse(provider.serves("jev-1.13.0"), "TypeSafe's own name is not served by a Kev server");
    }

    @Test
    void withNoCredentialConnectedTheProviderStatesNoFactsAndServesEverything() {
        SystemOneTestSecrets.host = null;
        SystemOneProvider provider = new SystemOneProvider();
        assertEquals("systemone-decision", provider.key(), "the key ends in -decision: the family");
        assertEquals("systemone", provider.platform());
        assertEquals(SystemOneClient.SECRET_ID, provider.credentialId());
        assertTrue(provider.connectionFacts().isEmpty(), "nothing is deployment-chosen yet");
        assertTrue(provider.serves("jev-1.13.0"), "with no endpoint to ask, nothing is dropped");
    }

    /** The local connection is the same wire under its own key and credential: an address alone, and no key on the wire. */
    @Test
    void theLocalProviderIsReachedByItsAddressAloneAndSendsNoKey() throws Exception {
        LocalSystemOneProvider provider = new LocalSystemOneProvider();
        assertEquals("systemone-local-decision", provider.key(), "the key ends in -decision: the family");
        assertEquals(ModelKind.DECISION, ModelKind.ofProviderKey(provider.key()));
        assertEquals("systemone", provider.platform(), "the same wire as the TypeSafe-compatible connection");
        assertEquals(SystemOneClient.LOCAL_ID, provider.credentialId());
        assertEquals(List.of(SystemOneClient.LOCAL_SHAPE), provider.credentialShapes());
        assertNull(SystemOneClient.LOCAL_SHAPE.secret(), "no key is asked for");
        assertNull(SystemOneClient.LOCAL_SHAPE.user());
        assertEquals("SYSTEMONE_LOCAL_HOST", SystemOneClient.LOCAL_SHAPE.host().variable());
        // a server of its own: the shared one is stopped by the test of an endpoint that does not list
        List<String> authorizations = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext(SystemOneModelListing.MODELS_PATH, exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(authorization == null ? "" : authorization);
            answer(exchange, 200, KEV);
        });
        local.start();
        try {
            String address = "http://127.0.0.1:" + local.getAddress().getPort();
            SystemOneTestSecrets.localHost = address;
            assertEquals(List.of("kev-latest"), provider.listModels().stream().map(DiscoveredModel::wireModelId).toList());
            assertEquals(List.of(""), authorizations, "the listing went out with no authorization header");
            assertTrue(provider.serves("kev-latest"));
            assertEquals(address, provider.connectionFacts().get("endpoint"));
        }
        finally {
            local.stop(0);
            SystemOneTestSecrets.localHost = null;
        }
    }

    @Test
    void anEndpointThatDoesNotListKeepsTheFailureAsAFactAndServesEverything() throws Exception {
        SystemOneTestSecrets.host = base + "/mute";
        SystemOneProvider provider = new SystemOneProvider();
        IOException failure = assertThrows(IOException.class, provider::listModels);
        assertTrue(failure.getMessage().contains("404"), failure.getMessage());
        Map<String, String> facts = provider.connectionFacts();
        assertEquals(base + "/mute", facts.get("endpoint"));
        assertTrue(facts.get("models").contains("404"), "the failure is the fact: " + facts);
        assertTrue(provider.serves("jev-1.13.0"), "an unread listing drops nothing; the ping judges");
    }
}
