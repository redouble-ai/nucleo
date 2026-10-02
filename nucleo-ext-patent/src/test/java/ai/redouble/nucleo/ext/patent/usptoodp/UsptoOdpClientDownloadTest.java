/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.usptoodp;

import ai.redouble.nucleo.harness.errors.*;
import com.sun.net.httpserver.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UsptoOdpClient#downloadXml} against a loopback server: a 2xx returns the body; a
 * status outside 2xx is the client's own classification, an {@link ExternalServiceException}
 * naming the status; and a failure of the download itself is wrapped with the service and the
 * step, an {@link ExternalServiceException} naming "USPTO ODP" and "downloading the grant XML",
 * never a {@link SystemException}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class UsptoOdpClientDownloadTest {
    private static HttpServer server;
    private static String base;
    private static UsptoOdpClient client;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serve("/grant.xml", 200, "<us-patent-grant><abstract><p>text</p></abstract></us-patent-grant>");
        serve("/broken.xml", 500, "server error");
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new UsptoOdpClient();
        client.setHttpClient(HttpClients.createDefault());
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void serve(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    @Test
    void aSuccessfulDownloadReturnsTheBody() throws Exception {
        assertTrue(client.downloadXml(base + "/grant.xml").contains("<abstract>"));
    }

    @Test
    void aStatusOutside2xxIsTheClientsOwnClassification() {
        ExternalServiceException e = assertThrows(ExternalServiceException.class, () -> client.downloadXml(base + "/broken.xml"));
        assertEquals("USPTO ODP", e.getServiceName());
        assertTrue(e.getErrorDetails().startsWith("Failed to download grant XML (HTTP 500)"), e.getErrorDetails());
    }

    @Test
    void aTransportFailureIsWrappedWithTheServiceAndTheStep() {
        // Port 1 on loopback answers nothing: the download fails before any status exists
        ExternalServiceException e = assertThrows(ExternalServiceException.class, () -> client.downloadXml("http://127.0.0.1:1/grant.xml"));
        assertEquals("USPTO ODP", e.getServiceName(), "the service is named");
        assertTrue(e.getErrorDetails().startsWith("downloading the grant XML: "), "the step is named: " + e.getErrorDetails());
        assertNotNull(e.getCause(), "the transport failure travels as the cause");
        for (Throwable t = e; t != null; t = t.getCause()) {
            assertFalse(t instanceof SystemException, "never an internal error anywhere in the chain: " + t);
        }
    }
}
