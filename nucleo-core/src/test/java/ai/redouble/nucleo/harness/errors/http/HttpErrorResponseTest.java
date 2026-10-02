/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors.http;

import ai.redouble.nucleo.harness.errors.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the invariants that make {@link HttpErrorResponse} safe.
 *
 * <p>First, the shadowing hazard: a method declared on a class always beats an
 * interface default, so if any of the five semantic parents ever grows a
 * {@code getStatusCode} / {@code getService} / {@code getEndpoint} /
 * {@code getResponseBody}, the accessors here would silently start returning
 * that parent's answer instead of the carrier's. Nothing about that failure is
 * visible at compile time, hence the assertions on every one of the eleven
 * carriers and the reflective check that no parent declares an accessor.
 *
 * <p>Second, each parent receives the string shaped for what it stores. The three
 * formatters on {@link HttpErrorDetail} differ precisely because each parent already
 * holds a different subset of the facts, and repeating one there would be noise.
 *
 * <p>Third, the endpoint is a path and never a request target. A client builds its target
 * by appending a query string, and that query carries what a caller supplied: a search
 * term, an identifier, whatever was typed. Every one of these messages is read by a model
 * and some of them cross a process boundary to a consumer we do not run, so an endpoint
 * that carried the query would hand a caller its own text back.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-17)
 */
public class HttpErrorResponseTest {

    private static final String SERVICE = "PubMed";
    private static final String ENDPOINT = "/entrez/efetch";
    private static final String BODY = "rate exceeded";

    private static final Set<Class<?>> CARRIERS = Set.of(
            Http400Exception.class, Http401Exception.class, Http403Exception.class, Http404Exception.class,
            Http413Exception.class, Http422Exception.class, Http429Exception.class, Http500Exception.class,
            Http502Exception.class, Http503Exception.class, HttpUnmappedStatusException.class);

    private static List<HttpErrorResponse> allEleven() {
        return allEleven(ENDPOINT);
    }

    private static List<HttpErrorResponse> allEleven(String endpoint) {
        return List.of(
                new Http400Exception(SERVICE, endpoint, BODY),
                new Http401Exception(SERVICE, endpoint, BODY),
                new Http403Exception(SERVICE, endpoint, BODY),
                new Http404Exception(SERVICE, endpoint, BODY),
                new Http413Exception(SERVICE, endpoint, BODY),
                new Http422Exception(SERVICE, endpoint, BODY),
                new Http429Exception(SERVICE, endpoint, BODY),
                new Http500Exception(SERVICE, endpoint, BODY),
                new Http502Exception(SERVICE, endpoint, BODY),
                new Http503Exception(SERVICE, endpoint, BODY),
                new HttpUnmappedStatusException(418, SERVICE, endpoint, BODY));
    }

    @Test
    void theElevenCarriersAreTheWholeImplementorSet() throws Exception {
        Set<Class<?>> found = new HashSet<>();
        for (Class<?> c : ErrorsClasspath.runtimeClasses()) {
            if (HttpErrorResponse.class.isAssignableFrom(c) && !c.isInterface()) {
                found.add(c);
            }
        }
        assertEquals(CARRIERS, found, "every implementor on the runtime's classpath is one of the eleven, and each of the eleven is one");
        Set<Class<?>> instantiated = new HashSet<>();
        for (HttpErrorResponse e : allEleven()) {
            instantiated.add(e.getClass());
        }
        assertEquals(CARRIERS, instantiated, "and the fixture enumerates all of them");
    }

    @Test
    void neitherACarrierNorAnyParentDeclaresAnAccessor() {
        List<String> accessors = List.of("getStatusCode", "getService", "getEndpoint", "getResponseBody");
        for (Class<?> carrier : CARRIERS) {
            for (Class<?> type = carrier; type != Object.class; type = type.getSuperclass()) {
                for (Method m : type.getDeclaredMethods()) {
                    assertFalse(accessors.contains(m.getName()) && m.getParameterCount() == 0,
                            type.getSimpleName() + "." + m.getName() + " would shadow the interface default");
                }
            }
        }
    }

    @Test
    void everyStatusExceptionReportsItsOwnResponse() {
        List<Integer> expected = List.of(400, 401, 403, 404, 413, 422, 429, 500, 502, 503, 418);
        List<HttpErrorResponse> eleven = allEleven();
        for (int i = 0; i < eleven.size(); i++) {
            HttpErrorResponse e = eleven.get(i);
            String who = e.getClass().getSimpleName();
            assertEquals(expected.get(i), e.getStatusCode(), who + " reports its own status");
            assertEquals(SERVICE, e.getService(), who + " reports the service");
            assertEquals(ENDPOINT, e.getEndpoint(), who + " reports the endpoint");
            assertEquals(BODY, e.getResponseBody(), who + " reports the raw body");
        }
    }

    @Test
    void everyStatusExceptionRendersTheSameLLMMessageShape() {
        for (HttpErrorResponse e : allEleven()) {
            LLMReadable readable = assertInstanceOf(LLMReadable.class, e);
            assertEquals("HTTP " + e.getStatusCode() + " from " + SERVICE + " at " + ENDPOINT + ": " + BODY,
                    readable.getLLMMessage(),
                    e.getClass().getSimpleName() + " names status, service, endpoint and body to the LLM");
        }
    }

    @Test
    void statusIsReadableWithoutKnowingTheSemanticParent() {
        // The point of the interface: five different parents, one way to ask what the server said.
        assertInstanceOf(InvalidInputException.class, new Http400Exception(SERVICE, ENDPOINT, BODY));
        assertInstanceOf(UnauthorizedException.class, new Http403Exception(SERVICE, ENDPOINT, BODY));
        assertInstanceOf(ResourceNotFoundException.class, new Http404Exception(SERVICE, ENDPOINT, BODY));
        assertInstanceOf(UpstreamThrottleException.class, new Http429Exception(SERVICE, ENDPOINT, BODY));
        assertInstanceOf(ExternalServiceException.class, new Http502Exception(SERVICE, ENDPOINT, BODY));
    }

    @Test
    void invalidInputParentKeepsEndpointAsParameterAndServiceInTheRule() {
        Http400Exception e = new Http400Exception(SERVICE, ENDPOINT, BODY);
        assertEquals(ENDPOINT, e.getParameterName(), "endpoint is what the LLM got wrong");
        assertEquals("HTTP 400 from " + SERVICE + ": " + BODY, e.getValidationRule(),
                "rule names the service; the endpoint is already the parameter name");
    }

    @Test
    void externalServiceParentKeepsServiceAndEndpointAnchoredDetail() {
        Http502Exception e = new Http502Exception(SERVICE, ENDPOINT, BODY);
        assertEquals(SERVICE, e.getServiceName());
        assertEquals("HTTP 502 at " + ENDPOINT + ": " + BODY, e.getErrorDetails(),
                "detail names the endpoint; the service is already stored separately");
    }

    @Test
    void notFoundParentKeepsServiceAndEndpointAsTheResourceIdentity() {
        Http404Exception e = new Http404Exception(SERVICE, ENDPOINT, BODY);
        assertEquals(SERVICE, e.getResourceType());
        assertEquals(ENDPOINT, e.getIdentifier());
    }

    @Test
    void throttleRetryAfterStaysOnTheParentAndSurvivesTheCarrier() {
        Http429Exception e = new Http429Exception(SERVICE, ENDPOINT, BODY, 42);
        assertEquals(42, e.getRetryAfterSeconds(), "the server's instruction is not part of its response payload");
        assertEquals(429, e.getStatusCode());
        assertEquals(SERVICE, e.getServiceName());
        assertEquals("HTTP 429 at " + ENDPOINT + ": " + BODY, e.getErrorDetails(), "the throttle parent stores the endpoint-anchored detail");
    }

    @Test
    void unauthorizedParentKeepsServiceAndEndpointAnchoredReason() {
        for (UnauthorizedException e : List.of(new Http401Exception(SERVICE, ENDPOINT, BODY), new Http403Exception(SERVICE, ENDPOINT, BODY))) {
            assertEquals(SERVICE, e.getServiceName());
            assertEquals("HTTP " + ((HttpErrorResponse) e).getStatusCode() + " at " + ENDPOINT + ": " + BODY, e.getReason(),
                    e.getClass().getSimpleName() + " stores the endpoint-anchored reason; the service is already stored separately");
        }
    }

    @Test
    void upstreamFailureReadsTheStatusOffTheInterface() {
        UpstreamFailure f = UpstreamFailure.from(new Http503Exception(SERVICE, ENDPOINT, BODY));
        assertEquals(503, f.statusCode());
        assertEquals(SERVICE, f.service());
        assertEquals("HTTP 503 at " + ENDPOINT + ": " + BODY, f.detail());
    }

    @Test
    void upstreamFailureRecordsZeroWhenThereWasNoHttpResponseAtAll() {
        // A connection refused / read timeout genuinely has no status. 0 records that fact
        // rather than standing in for one.
        UpstreamFailure f = UpstreamFailure.from(
                new ExternalServiceException(SERVICE, "connection refused"));
        assertEquals(0, f.statusCode());
        assertEquals("connection refused", f.detail());
    }

    @Test
    void carrierIsBuiltOnceAndReturnedIdentically() {
        for (HttpErrorResponse e : allEleven()) {
            assertSame(e.http(), e.http(), e.getClass().getSimpleName() + " returns the one record built at construction");
        }
    }

    @Test
    void aRequestTargetIsReducedToItsEndpointSoNoQueryReachesAMessage() {
        // A client passes what it sent, which is the path plus the query it built from the
        // caller's arguments. What an error may name is where it failed, so the query is cut
        // at the one place every one of these details is built.
        String term = "pembrolizumab NSCLC";
        HttpErrorDetail detail = new HttpErrorDetail(400, SERVICE, "/studies?query.term=" + term + "&pageSize=20", BODY);
        assertEquals("/studies", detail.endpoint());
        for (String message : List.of(detail.atEndpoint(), detail.fromService(), detail.llmMessage())) {
            assertFalse(message.contains(term), "a caller's own argument came back: " + message);
            assertTrue(message.contains(BODY), "the service's answer stays: " + message);
        }
        for (HttpErrorResponse carrier : allEleven("/studies?q=" + term)) {
            assertEquals("/studies", carrier.getEndpoint(),
                    carrier.getClass().getSimpleName() + " is built through that same detail, so its endpoint is the path alone");
        }
    }

    @Test
    void anEndpointWithNoQueryIsUntouched() {
        assertEquals(ENDPOINT, new HttpErrorDetail(500, SERVICE, ENDPOINT, BODY).endpoint());
    }
}
