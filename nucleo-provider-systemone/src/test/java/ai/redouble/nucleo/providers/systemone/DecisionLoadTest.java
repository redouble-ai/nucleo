/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.systemone;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.tools.deciding.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The harness under load, against a decision entry bounded by one request in flight: two
 * hundred decisions submitted at once all complete, the server never sees more than one at a
 * time, and nothing is dropped, because admission is a line with no limit and no clock. A
 * server that dies mid-run fails every remaining decision by name within its own timeout,
 * none hangs, and a server back on the same port serves the next batch. The catalog is this
 * module's test catalog, one entry pinned as the decision; the credential store is the test's
 * own, pointing at the server the test runs.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
class DecisionLoadTest {
    static final String ANSWER = "{\"model\":\"kev-latest\",\"answers\":{\"ok\":{\"type\":\"noul\",\"noul\":0.9}},\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}";
    private static HttpServer server;
    private static int port;
    private static final AtomicInteger inFlight = new AtomicInteger();
    private static final AtomicInteger peak = new AtomicInteger();
    private static final AtomicInteger served = new AtomicInteger();
    private static volatile long serviceMillis = 15;

    @BeforeAll
    static void start() throws IOException {
        server = serve(0);
        port = server.getAddress().getPort();
        SystemOneTestSecrets.host = "http://127.0.0.1:" + port;
        JobDispatcher.getInstance().start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    /** A server on the port that answers every decision after a short service time, counting what is in flight. */
    private static HttpServer serve(int onPort) throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", onPort), 0);
        created.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        created.createContext(SystemOneWire.PATH, exchange -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                exchange.getRequestBody().readAllBytes();
                Thread.sleep(serviceMillis);
                byte[] bytes = ANSWER.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
                served.incrementAndGet();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finally {
                inFlight.decrementAndGet();
            }
        });
        created.start();
        return created;
    }

    private static DecisionCall call(Identifiable root) {
        DecisionCall call = new DecisionCall(root, new DecisionRequest("a ticket", Map.of("ok", Noul.of("Is it fine?"))));
        // a dead server is the case under test; pacing three re-runs would only stretch the run
        call.setUpstreamRetries(0);
        return call;
    }

    @Test
    void twoHundredDecisionsAtOnceAllComplete_andTheServerSeesOneAtATime() throws Exception {
        peak.set(0);
        int before = served.get();
        Identifiable root = Job.workflow("load", "load");
        List<JobHandle<DecisionResponse>> handles = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            handles.add(JobDispatcher.getInstance().submit(call(root)));
        }
        for (JobHandle<DecisionResponse> handle : handles) {
            DecisionResponse response = handle.get(120, TimeUnit.SECONDS);
            assertEquals(0.9, response.noul("ok").probability(), 1e-9);
            assertEquals(5, response.getActualInputTokens());
        }
        assertEquals(200, served.get() - before, "every decision reached the server exactly once");
        assertEquals(1, peak.get(), "an entry of max_concurrent 1 never has two requests in flight");
    }

    @Test
    void aServerThatDiesMidRunFailsTheRestByName_andARestartServesAgain() throws Exception {
        serviceMillis = 40;
        try {
            Identifiable root = Job.workflow("outage", "outage");
            List<JobHandle<DecisionResponse>> handles = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                handles.add(JobDispatcher.getInstance().submit(call(root)));
            }
            int before = served.get();
            while (served.get() - before < 5) {
                Thread.sleep(10);
            }
            server.stop(0);
            int completed = 0;
            int failed = 0;
            for (JobHandle<DecisionResponse> handle : handles) {
                try {
                    handle.get(30, TimeUnit.SECONDS);
                    completed++;
                }
                catch (ExecutionException e) {
                    failed++;
                    String chain = chain(e);
                    assertTrue(chain.contains("could not be reached") || chain.contains("HTTP 5") || chain.contains("not an answer"),
                            "a dead server is named as such: " + chain);
                    assertTrue(hasCause(e, LLMReadable.class), "the failure speaks to a model: " + chain);
                }
            }
            assertEquals(40, completed + failed, "every decision settled, none hung");
            assertTrue(failed > 0, "the outage failed the decisions still in line");
            server = serve(port);
            List<JobHandle<DecisionResponse>> again = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                again.add(JobDispatcher.getInstance().submit(call(root)));
            }
            for (JobHandle<DecisionResponse> handle : again) {
                assertEquals(0.9, handle.get(60, TimeUnit.SECONDS).noul("ok").probability(), 1e-9, "the server back on its port serves again");
            }
        }
        finally {
            serviceMillis = 15;
        }
    }

    private static boolean hasCause(Throwable t, Class<?> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }
}
