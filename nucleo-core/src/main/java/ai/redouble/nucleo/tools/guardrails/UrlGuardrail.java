/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.guardrails;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;

import java.net.*;
import java.time.*;

/**
 * Guardrail that validates URLs against private/reserved IP ranges.
 * <p>
 * Blocks URLs that resolve to:
 * <ul>
 *   <li>Loopback (127.0.0.0/8, ::1)</li>
 *   <li>RFC 1918 private ranges (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)</li>
 *   <li>Link-local (169.254.0.0/16, fe80::/10) - includes cloud metadata endpoints</li>
 *   <li>Any other address where {@link InetAddress#isSiteLocalAddress()},
 *       {@link InetAddress#isLoopbackAddress()}, or {@link InetAddress#isLinkLocalAddress()} is true</li>
 * </ul>
 * <p>
 * Applies to any tool input that implements {@link UrlInput}. Tools with URL parameters
 * should have their input implement that interface.
 * <p>
 * Override {@link #isBlocked(String, InetAddress)} to customize the policy (e.g., add
 * domain allowlists for external-facing deployments).
 * <p>
 * Note: this performs DNS resolution at validation time. DNS rebinding attacks can bypass
 * this if the hostname resolves differently at connect time. For untrusted environments,
 * network-level isolation (proxy/DMZ) is the proper defense.
 * <p>
 * Each refusal names the parameter and the rule it broke and never the value it was given,
 * matching {@link ai.redouble.nucleo.harness.errors.InvalidInputException}: the model authored the URL and
 * needs the rule to fix it, while the string itself may be anything it pasted into the field
 * and does not belong in a message that is logged.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-13)
 */
public class UrlGuardrail extends AbstractContentGuardrail<UrlInput> {

    public UrlGuardrail(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofSeconds(5));
    }

    @Override
    public Direction direction() {
        return Direction.INPUT;
    }

    @Override
    public Class<UrlInput> targetType() {
        return UrlInput.class;
    }

    @Override
    public void validate(UrlInput input) throws GuardrailException {
        String url = input.getUrl();
        if (url == null || url.isBlank()) {
            throw new GuardrailException("Validation failed for parameter 'url': a non-blank URL is required");
        }
        URI uri;
        try {
            uri = new URI(url);
        }
        catch (URISyntaxException e) {
            throw new GuardrailException("Validation failed for parameter 'url': must be a syntactically valid URI");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new GuardrailException("Validation failed for parameter 'url': must carry a host component");
        }
        InetAddress addr;
        try {
            addr = InetAddress.getByName(host);
        }
        catch (UnknownHostException e) {
            throw new GuardrailException("Validation failed for parameter 'url': its host must resolve in DNS");
        }
        if (isBlocked(host, addr)) {
            throw new GuardrailException("Validation failed for parameter 'url': must resolve to a public address, "
                    + "never a loopback, private or link-local one");
        }
    }

    /**
     * Determines if a resolved address should be blocked.
     * Override to customize the policy.
     * <p>
     * Default implementation blocks loopback, site-local (RFC 1918), and link-local addresses.
     *
     * @param host the hostname from the URL
     * @param addr the resolved address
     * @return true if the URL should be blocked
     */
    protected boolean isBlocked(String host, InetAddress addr) {
        return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress();
    }
}
