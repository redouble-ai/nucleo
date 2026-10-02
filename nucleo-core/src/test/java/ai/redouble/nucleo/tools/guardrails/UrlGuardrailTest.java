/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.guardrails;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import org.junit.jupiter.api.*;

import java.net.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The acceptance criteria for the guardrail that stands between an agent-authored URL and
 * the network.
 *
 * <p><b>Anything the policy does not admit is refused.</b> The blocked set is the one the
 * class documents: loopback, RFC 1918 site-local, and link-local, which is what puts the
 * cloud metadata endpoint out of reach. Refusal is by resolved address rather than by
 * spelling, so the decimal, the shorthand and the IPv6 forms of one address are one rule
 * and not three. The mirror side holds: a public address is admitted, and admitting it is
 * what makes the refusals mean something.
 *
 * <p><b>No refusal repeats what it was handed.</b> Every message names the parameter and
 * the rule it broke, in the shape {@code InvalidInputException} already uses, and carries
 * no fragment of the URL. This is checked by construction: every payload carries a canary
 * and the assertion is that no canary reaches any message in the cause chain.
 *
 * <p>What this does not pin is the DNS-rebinding gap the class documents: the policy reads
 * the address at validation time and a name that resolves differently at connect time
 * defeats it. That is stated as a limitation rather than tested as a behaviour, because
 * network-level isolation is what closes it and no assertion here could.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
public class UrlGuardrailTest {
    private static final Identifiable TEST_ROOT = Job.workflow("test-user", "url-guardrail-test");
    /** Distinctive enough that finding it in a message can mean nothing else. */
    private static final String CANARY = "zqCanaryPath9173";

    record Fetch(String url) implements UrlInput {
        @Override
        public String getUrl() {
            return url;
        }
    }

    record Payload(String name, String url) {
    }

    /**
     * Addresses the documented policy refuses, each carrying the canary in a component the
     * guardrail never inspects, so a message that quotes its input is caught wherever it
     * chose to quote from.
     */
    static List<Payload> restricted() {
        return List.of(
                new Payload("IPv4 loopback", "http://127.0.0.1/" + CANARY),
                new Payload("loopback under another octet", "http://127.9.9.9/" + CANARY),
                new Payload("IPv6 loopback", "http://[::1]/" + CANARY),
                new Payload("the name that resolves to loopback", "http://localhost/" + CANARY),
                new Payload("RFC 1918 ten-dot", "http://10.0.0.1/" + CANARY),
                new Payload("RFC 1918 one-seven-two", "http://172.16.0.1/" + CANARY),
                new Payload("RFC 1918 one-nine-two", "http://192.168.1.1/" + CANARY),
                new Payload("link-local, the cloud metadata endpoint", "http://169.254.169.254/" + CANARY),
                new Payload("metadata endpoint on a non-default port", "http://169.254.169.254:8080/" + CANARY));
    }

    /** Malformed or incomplete URLs, refused before any address is resolved. */
    static List<Payload> unusable() {
        return List.of(
                new Payload("a space inside the authority", "http://exa mple.com/" + CANARY),
                new Payload("no host component at all", "file:///etc/" + CANARY),
                new Payload("a scheme with no authority", "mailto:" + CANARY + "@example.com"),
                new Payload("a host that must never resolve", "http://" + CANARY + ".invalid/"));
    }

    private static GuardrailException refusalFor(String url) {
        UrlGuardrail guard = new UrlGuardrail(TEST_ROOT);
        return assertThrows(GuardrailException.class, () -> guard.validate(new Fetch(url)),
                "a URL outside the documented policy must be refused rather than fetched: " + url);
    }

    @Test
    void everyRestrictedAddressIsRefusedHoweverItIsSpelled() {
        for (Payload payload : restricted()) {
            GuardrailException refusal = refusalFor(payload.url());
            assertTrue(refusal.getMessage().contains("url"),
                    payload.name() + ": the refusal names the parameter the caller can fix");
        }
        assertEquals(9, restricted().size(), "the restricted corpus is the one the class documents");
    }

    @Test
    void everyUnusableUrlIsRefusedBeforeAnyAddressIsResolved() {
        for (Payload payload : unusable()) {
            GuardrailException refusal = refusalFor(payload.url());
            assertTrue(refusal.getMessage().contains("url"),
                    payload.name() + ": the refusal names the parameter the caller can fix");
        }
        assertEquals(4, unusable().size(), "the unusable corpus is the one the class documents");
    }

    @Test
    void aMissingUrlIsRefusedAndSoIsABlankOne() {
        assertTrue(refusalFor(null).getMessage().contains("non-blank"),
                "a null url is refused with the rule that would have admitted it");
        assertTrue(refusalFor("").getMessage().contains("non-blank"),
                "an empty url is refused with the rule that would have admitted it");
        assertTrue(refusalFor("   ").getMessage().contains("non-blank"),
                "a whitespace-only url is refused with the rule that would have admitted it");
    }

    @Test
    void noRefusalQuotesTheUrlItWasHanded() throws Exception {
        List<Payload> all = new ArrayList<>(restricted());
        all.addAll(unusable());
        for (Payload payload : all) {
            GuardrailException refusal = refusalFor(payload.url());
            for (Throwable t = refusal; t != null; t = t.getCause()) {
                assertFalse(String.valueOf(t.getMessage()).contains(CANARY),
                        payload.name() + ": the refusal is composed from our parameter and our rule, "
                                + "never from what the caller sent - found the canary in " + t.getClass().getSimpleName());
                assertFalse(String.valueOf(t.getMessage()).contains(payload.url()),
                        payload.name() + ": the whole URL must not appear either");
            }
            assertFalse(refusal.getLLMMessage().contains(CANARY),
                    payload.name() + ": the LLM-facing message is composed the same way");
        }
    }

    @Test
    void everyRefusalNamesWhatWouldHaveBeenAccepted() {
        assertTrue(refusalFor("http://127.0.0.1/" + CANARY).getMessage().contains("public address"),
                "a caller told only 'restricted' cannot tell what would work");
        assertTrue(refusalFor("http://exa mple.com/x").getMessage().contains("valid URI"),
                "a malformed URL is refused by naming the shape that is accepted");
        assertTrue(refusalFor("file:///etc/passwd").getMessage().contains("host"),
                "a hostless URL is refused by naming the component that is missing");
    }

    @Test
    void aPublicAddressIsAdmitted() throws Exception {
        UrlGuardrail guard = new UrlGuardrail(TEST_ROOT);
        assertDoesNotThrow(() -> guard.validate(new Fetch("http://8.8.8.8/" + CANARY)),
                "a public literal is outside every blocked range and must pass");
        assertDoesNotThrow(() -> guard.validate(new Fetch("https://1.1.1.1:8443/a/b?q=1")),
                "the policy judges the address, so a port and a query change nothing");
    }

    @Test
    void aSubclassWidensThePolicyAndTheBaseRulesStillRun() {
        UrlGuardrail allowlist = new UrlGuardrail(TEST_ROOT) {
            @Override
            protected boolean isBlocked(String host, InetAddress addr) {
                return super.isBlocked(host, addr) || !host.equals("8.8.8.8");
            }
        };
        assertDoesNotThrow(() -> allowlist.validate(new Fetch("http://8.8.8.8/ok")),
                "the one host the subclass admits still passes");
        assertThrows(GuardrailException.class, () -> allowlist.validate(new Fetch("http://1.1.1.1/x")),
                "a public address the subclass excludes is refused, which is the documented extension point");
        assertThrows(GuardrailException.class, () -> allowlist.validate(new Fetch("http://127.0.0.1/x")),
                "widening the policy does not reopen what the base class blocks");
    }

    @Test
    void theGuardrailIsAnInputSideReadOnlyCheck() {
        UrlGuardrail guard = new UrlGuardrail(TEST_ROOT);
        assertEquals(ContentGuardrail.Direction.INPUT, guard.direction(),
                "a URL is judged before the tool runs, never after it has already fetched");
        assertTrue(guard.getRequirements().isReadOnly(),
                "resolving a name reads and changes nothing, so the check itself declares read-only");
        assertEquals(UrlInput.class, guard.targetType(),
                "the guardrail applies to every input that declares it carries a URL");
    }
}
