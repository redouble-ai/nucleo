/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.builtin;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.guardrails.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tool that reaches outside the process, driven against a real server on loopback so the
 * transport, the status mapping and the extraction all run.
 *
 * <p><b>Every upstream status becomes the framework type its class deserves.</b> A 400 is the
 * caller's to fix and arrives correctable; a 401 or 403 is an authorization failure against
 * that host; a 404 is a resource that does not exist; anything else is the service's fault
 * and uncorrectable. A raw {@code IOException} or a half-built output on any of them would
 * leave a thinker unable to tell "ask differently" from "give up on this tool".
 *
 * <p><b>The declared ceiling is applied and said out loud.</b> Content past {@code maxLength}
 * is cut, and the cut is marked in the text rather than left for the model to discover, so a
 * truncated page is never mistaken for a short one.
 *
 * <p><b>The tool carries its own address policy.</b> It declares {@link UrlGuardrail}, so a
 * dispatched call to loopback, a private range or a metadata endpoint is refused before any
 * request is made, with nothing for a deployment to remember to switch on. The same policy
 * judges every redirect the tool follows, so a page the policy admits cannot lead the fetch
 * to one it refuses; a chain of redirects is followed up to a stated number and no further.
 * The tests reach their own loopback server through a subclass whose policy admits that one
 * host, which is also the documented way a deployment sets a policy of its own.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
public class WebFetchToolTest {
    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void startEverything() throws Exception {
        JobDispatcher.getInstance().start();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serve("/page", 200, """
                <html><head><title>A Title</title>
                <meta name="description" content="the meta description">
                </head><body><nav>skip me</nav><main><p>the real content</p></main>
                <script>alert('no')</script></body></html>""");
        serve("/long", 200, "<html><body><main>" + "z".repeat(5_000) + "</main></body></html>");
        serve("/status/400", 400, "bad request");
        serve("/status/401", 401, "unauthorized");
        serve("/status/403", 403, "forbidden");
        serve("/status/404", 404, "not found");
        serve("/status/422", 422, "unprocessable");
        serve("/status/500", 500, "server error");
        serve("/status/503", 503, "unavailable");
        redirect("/moved", "/page");
        redirect("/loop", "/loop");
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        // the same server under the one name the test policy does not admit
        redirect("/escape", "http://localhost:" + server.getAddress().getPort() + "/page");
    }

    private static void redirect(String path, String location) {
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
    }

    /** The tool under a policy that admits the test server's literal address and nothing else private. */
    static class LocalFetchTool extends WebFetchTool {
        LocalFetchTool(Identifiable parent) {
            super(parent);
        }

        @Override
        protected UrlGuardrail addressPolicy() {
            return new UrlGuardrail(this) {
                @Override
                protected boolean isBlocked(String host, InetAddress addr) {
                    return !host.equals("127.0.0.1") && super.isBlocked(host, addr);
                }
            };
        }
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void serve(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private static Identifiable root() {
        return Job.workflow("web-fetch-test", "web-fetch-test");
    }

    private static WebFetchOutput fetch(String url, Integer maxLength) throws Exception {
        return fetch(new LocalFetchTool(root()), url, maxLength);
    }

    private static WebFetchOutput fetch(WebFetchTool tool, String url, Integer maxLength) throws Exception {
        WebFetchInput input = new WebFetchInput();
        input.setUrl(url);
        input.setMaxLength(maxLength);
        tool.setInput(input);
        return JobDispatcher.getInstance().submit(tool).get();
    }

    /** The dispatcher delivers a job's failure wrapped, so the assertion walks to the real one. */
    private static Throwable failureFor(String url) {
        ExecutionException wrapper = assertThrows(ExecutionException.class, () -> fetch(url, null),
                "a status the server refused on must reach the caller as a failure, never as an empty page");
        Throwable cause = wrapper.getCause();
        assertNotNull(cause, "the failure carries the reason it failed");
        return cause;
    }

    private static boolean chainHas(Throwable start, Class<?> type) {
        for (Throwable t = start; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aPageIsFetchedAndReducedToItsReadableContent() throws Exception {
        WebPageArtifact page = fetch(base + "/page", null).getPage();
        assertNotNull(page, "a 200 produces a page artifact");
        assertEquals("A Title", page.getTitle(), "the title comes from the document head");
        assertEquals("the meta description", page.getDescription(),
                "a meta description is preferred over the first paragraph");
        assertTrue(page.getContent().contains("the real content"), "the main content survives extraction");
        assertFalse(page.getContent().contains("skip me"),
                "navigation is removed, because it is chrome rather than content");
        assertFalse(page.getContent().contains("alert"),
                "scripts are removed, because their text is not what the model was asked to read");
        assertEquals("fetch", page.getSource(), "the artifact records how it was obtained");
        assertEquals(base + "/page", page.getUrl(), "the artifact records where it came from");
    }

    @Test
    void contentPastTheDeclaredCeilingIsCutAndTheCutIsStated() throws Exception {
        WebPageArtifact page = fetch(base + "/long", 100).getPage();
        assertTrue(page.getContent().startsWith("z".repeat(100)),
                "the first maxLength characters are the ones kept");
        assertTrue(page.getContent().contains("[Content truncated at 100 characters]"),
                "a truncated page says so, so the model never reads a cut page as a complete one");
    }

    @Test
    void aPageInsideTheCeilingIsNotMarkedTruncated() throws Exception {
        WebPageArtifact page = fetch(base + "/page", 50_000).getPage();
        assertFalse(page.getContent().contains("truncated"),
                "a page that fits is delivered whole and unannotated");
    }

    @Test
    void aBadRequestIsCorrectableBecauseTheCallerCanChangeIt() {
        assertTrue(chainHas(failureFor(base + "/status/400"), InvalidInputException.class),
                "a 400 is the caller's URL to fix, so it arrives as the correctable type");
        assertTrue(chainHas(failureFor(base + "/status/422"), InvalidInputException.class),
                "a 422 is the same class of failure as a 400 and maps the same way");
    }

    @Test
    void anAuthorizationFailureIsNamedAsOne() {
        assertTrue(chainHas(failureFor(base + "/status/401"), UnauthorizedException.class),
                "a 401 is an authorization failure against that host, not a missing page");
        assertTrue(chainHas(failureFor(base + "/status/403"), UnauthorizedException.class),
                "a 403 maps the same way as a 401");
    }

    @Test
    void aMissingPageIsNotFoundRatherThanAServiceFailure() {
        Throwable failure = failureFor(base + "/status/404");
        assertTrue(chainHas(failure, ResourceNotFoundException.class),
                "fetching one URL that does not exist is not-found, which the model can act on");
        assertFalse(chainHas(failure, ExternalServiceException.class),
                "a 404 is never reported as the service being broken");
    }

    @Test
    void aServerFailureIsUncorrectableBecauseNoInputChangeFixesIt() {
        assertTrue(chainHas(failureFor(base + "/status/500"), ExternalServiceException.class),
                "a 500 is the service's fault, so retrying the same tool with different input is pointless");
        assertTrue(chainHas(failureFor(base + "/status/503"), ExternalServiceException.class),
                "a 503 falls to the same default arm as any other unmapped status");
    }

    @Test
    void noFailureArrivesAsARawIoExceptionOrANullOutput() {
        for (String status : new String[] {"400", "401", "403", "404", "422", "500", "503"}) {
            Throwable failure = failureFor(base + "/status/" + status);
            assertTrue(failure instanceof LLMReadable,
                    status + ": every refusal reaches a thinker as an LLM-readable type, never a bare exception");
            assertNotNull(((LLMReadable) failure).getLLMMessage(),
                    status + ": the type carries a message the model can act on");
        }
    }

    @Test
    void theToolAsShippedRefusesAPrivateAddressBeforeAnyRequestIsMade() {
        ExecutionException refused = assertThrows(ExecutionException.class, () -> fetch(new WebFetchTool(root()), base + "/page", null),
                "the tool declares its own address policy, so a dispatched call to loopback never reaches the network");
        assertTrue(chainHas(refused, ai.redouble.nucleo.guardrails.GuardrailException.class),
                "the refusal is the guardrail's, correctable by the model that wrote the URL: " + refused);
        assertEquals(1, new WebFetchTool(root()).declareContentGuardrails().size(), "one policy, declared by the tool itself");
    }

    @Test
    void aRedirectThePolicyAdmitsIsFollowedToThePage() throws Exception {
        WebPageArtifact page = fetch(base + "/moved", null).getPage();
        assertEquals("A Title", page.getTitle(), "the page the redirect led to is the page returned");
        assertEquals(base + "/page", page.getUrl(), "and the artifact names the address that was fetched in the end");
    }

    @Test
    void aRedirectToAnAddressThePolicyRefusesIsNotFollowed() {
        ExecutionException refused = assertThrows(ExecutionException.class, () -> fetch(base + "/escape", null),
                "a page the policy admits must not be able to send the fetch to an address it refuses");
        assertTrue(chainHas(refused, ai.redouble.nucleo.guardrails.GuardrailException.class),
                "the hop is refused by the same policy that judged the first address: " + refused);
    }

    @Test
    void aRedirectChainPastTheLimitIsTheServicesFailure() {
        Throwable failure = failureFor(base + "/loop");
        assertTrue(chainHas(failure, ExternalServiceException.class), "a loop of redirects ends as a service failure: " + failure);
        assertTrue(String.valueOf(failure.getMessage()).contains(String.valueOf(WebFetchTool.MAX_REDIRECTS)),
                "and the failure states how many redirects were followed: " + failure.getMessage());
    }

    @Test
    void aTransportFailureIsTheServicesFaultAndNamesTheStepNeverAnInternalError() {
        // Port 1 on loopback answers nothing: the transport fails before any status exists
        String unreachable = "http://127.0.0.1:1/page";
        Throwable failure = failureFor(unreachable);
        ExternalServiceException external = null;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ExternalServiceException e) {
                external = e;
                break;
            }
        }
        assertNotNull(external, "a raw transport failure is wrapped as the service's failure: " + failure);
        assertEquals("web:" + unreachable, external.getServiceName(), "the service is named");
        assertTrue(external.getErrorDetails().startsWith("fetching the page: "), "the step is named: " + external.getErrorDetails());
        assertFalse(chainHas(failure, SystemException.class), "never an internal error for a URL the model chose");
    }

    @Test
    void theToolDeclaresItselfReadOnlyAndTransactionless() {
        JobRequirements req = new WebFetchTool(root()).getRequirements();
        assertTrue(req.isReadOnly(), "fetching a page changes nothing on our side");
        assertFalse(req.requiresTransaction(), "there is no database work to wrap");
    }
}
