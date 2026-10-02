/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp;

import io.modelcontextprotocol.spec.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The identity a client presents in the MCP initialize handshake.
 *
 * <p>The identity belongs to the deployment's application: {@link GenericMCPClient#identifyAs}
 * sets it once at startup, and until it does, the runtime introduces itself as {@code nucleo}
 * at the library's version. A blank name or version is refused, because servers log and meter
 * by this pair.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
class GenericMCPClientIdentityTest {
    private McpSchema.Implementation before;

    @BeforeEach
    void rememberIdentity() {
        before = GenericMCPClient.clientIdentity();
    }

    @AfterEach
    void restoreIdentity() {
        GenericMCPClient.identifyAs(before.name(), before.version());
    }

    @Test
    void theRuntimeIntroducesItselfAsNucleoUntilAHostSetsItsOwnName() {
        assertEquals("nucleo", GenericMCPClient.clientIdentity().name(),
                "an unconfigured host presents the library's own name");
        assertNotNull(GenericMCPClient.clientIdentity().version(),
                "the default identity carries the library's version");
    }

    @Test
    void identifyAsReplacesWhatTheNextHandshakePresents() {
        GenericMCPClient.identifyAs("acme-claims", "3.2");
        assertEquals("acme-claims", GenericMCPClient.clientIdentity().name(),
                "the handshake presents the application's name once the host sets it");
        assertEquals("3.2", GenericMCPClient.clientIdentity().version(),
                "the handshake presents the application's version once the host sets it");
    }

    @Test
    void aBlankNameOrVersionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> GenericMCPClient.identifyAs(" ", "1.0"),
                "a blank name is refused - servers log and meter by it");
        assertThrows(IllegalArgumentException.class, () -> GenericMCPClient.identifyAs("acme", null),
                "a null version is refused - servers log and meter by it");
    }
}
