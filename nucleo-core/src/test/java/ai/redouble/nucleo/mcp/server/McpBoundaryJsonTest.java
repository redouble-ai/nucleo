/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.spec.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.charset.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The raw tier of the boundary campaign: payloads as bytes, judged by the decoder.
 *
 * <p>{@code McpHostileInputTest} drives objects and so tests the gate; it cannot reach any
 * of this. By the time the gate runs, a document has been parsed and the parser's decisions
 * are already made and invisible - which of two duplicate keys survived, whether bytes after
 * the document were ignored. A corpus that never sends bytes has not tested the decoder.
 *
 * <p>The sharp one is duplicate keys. Jackson's default keeps the last silently, so
 * {@code {"amount":1,"amount":9999}} arrives as one well-formed field and passes every check
 * the gate has. Nothing downstream can recover the fact that two were sent.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public class McpBoundaryJsonTest {

    private static final ObjectMapper STRICT = McpBoundaryJson.strictObjectMapper();

    record Raw(String name, String document) {
    }

    static Stream<Raw> refusable() {
        return Stream.of(
                new Raw("duplicate keys with different values", "{\"amount\":1,\"amount\":9999}"),
                new Raw("duplicate keys with the same value", "{\"amount\":1,\"amount\":1}"),
                new Raw("a duplicate key nested", "{\"outer\":{\"a\":1,\"a\":2}}"),
                new Raw("trailing garbage after the document", "{\"a\":1} not json"),
                new Raw("two documents back to back", "{\"a\":1}{\"b\":2}"),
                new Raw("a trailing comma", "{\"a\":1,}"),
                new Raw("a block comment", "{\"a\":1 /* hello */}"),
                new Raw("a line comment", "{\"a\":1} // hello"),
                new Raw("single quotes", "{'a':1}"),
                new Raw("an unquoted field name", "{a:1}"),
                new Raw("NaN", "{\"a\":NaN}"),
                new Raw("Infinity", "{\"a\":Infinity}"),
                new Raw("a leading zero", "{\"a\":01}"),
                new Raw("a raw control character in a string", "{\"a\":\"x" + (char)0x01 + "y\"}"),
                new Raw("truncated json", "{\"a\":"),
                new Raw("an empty document", ""),
                new Raw("whitespace only", "   "));
    }

    /**
     * Read the way the transport reads - {@code readValue} into a type, not {@code readTree}
     * - because that is the call whose strictness matters and the two differ: {@code readTree}
     * answers an empty document with a missing node instead of refusing it.
     */
    @TestFactory
    Stream<DynamicTest> theBoundaryDecoderRefusesEveryOneOfThese() {
        return refusable().map(raw -> DynamicTest.dynamicTest(raw.name(), () ->
                assertThrows(IOException.class, () -> STRICT.readValue(raw.document(), JsonNode.class),
                        () -> raw.name() + ": the decoder accepted it")));
    }

    @Test
    void nestingIsBoundedByTheDecoderSoNothingAboveItNeedsToBe() {
        // Jackson's StreamReadConstraints caps nesting at 1000, which is the stack-exhaustion
        // bound its own authors chose. Everything downstream - the gate's recursion, the
        // deserializer's - runs inside that guarantee, so none of them carries a second,
        // tighter number of its own.
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            deep.append("{\"a\":");
        }
        deep.append("1");
        for (int i = 0; i < 2000; i++) {
            deep.append("}");
        }
        assertThrows(IOException.class, () -> STRICT.readValue(deep.toString(), JsonNode.class));
    }

    @Test
    void everyDecoderBoundIsPinnedSoALibraryCannotMoveOneQuietly() {
        // The gate checks no size, no depth and no length because these three do, one layer
        // below it. They are Jackson's defaults rather than numbers of ours, which is exactly
        // why they are pinned here: a library that changes its mind fails the build instead
        // of the boundary. Document length is deliberately unbounded; what a caller may send
        // is the host's transport limit, not a number this package invents.
        StreamReadConstraints constraints = STRICT.getFactory().streamReadConstraints();
        assertEquals(1000, constraints.getMaxNestingDepth(), "the stack-exhaustion bound the gate's recursion runs inside");
        assertEquals(20_000_000, constraints.getMaxStringLength(), "one string cannot exhaust the heap on its own");
        assertEquals(1000, constraints.getMaxNumberLength(), "a number no width can hold is refused before it is built");
    }

    @Test
    void nestingWithinThatBoundIsRead() throws Exception {
        StringBuilder ok = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            ok.append("{\"a\":");
        }
        ok.append("1");
        for (int i = 0; i < 100; i++) {
            ok.append("}");
        }
        assertNotNull(STRICT.readValue(ok.toString(), JsonNode.class));
    }

    @Test
    void aCleanDocumentStillReads() throws Exception {
        JsonNode node = STRICT.readValue("{\"a\":1,\"b\":[1,2],\"c\":{\"d\":\"x\"}}", JsonNode.class);
        assertEquals(1, node.get("a").asInt());
        assertEquals(2, node.get("b").size());
        assertEquals("x", node.get("c").get("d").asText());
    }

    @Test
    void anUnknownPropertyIsLeftForTheGateToRefuse() throws Exception {
        // Failing here would answer a parser's message; the gate answers with the accepted
        // names instead, so the decoder deliberately does not fail on unknown properties.
        assertFalse(STRICT.getDeserializationConfig()
                .isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    @Test
    void invalidUtf8IsRefusedRatherThanReplaced() {
        // A lone 0x80 continuation byte is not UTF-8. Decoding it as replacement characters
        // would hand the gate a string the caller never sent.
        byte[] bytes = {'{', '"', 'a', '"', ':', '"', (byte)0x80, '"', '}'};
        assertThrows(IOException.class, () -> STRICT.readTree(bytes));
    }

    @Test
    void aByteOrderMarkIsRefused() {
        String withBom = '﻿' + "{\"a\":1}";
        assertThrows(IOException.class, () -> STRICT.readTree(withBom.getBytes(StandardCharsets.UTF_16)));
    }

    @Test
    void anEnvelopeWithADuplicateKeyNeverReachesTheHandler() {
        // The shape that matters: a JSON-RPC envelope whose params carry the same key twice.
        // Refused at the decoder, so no handler, no gate and no tool ever sees a value that
        // silently replaced another.
        String envelope = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + McpSchema.METHOD_TOOLS_CALL
                + "\",\"params\":{\"name\":\"mcp_strict\",\"arguments\":{\"text\":\"a\",\"text\":\"b\"}}}";
        assertThrows(IOException.class, () -> STRICT.readValue(envelope, McpSchema.JSONRPCRequest.class));
    }
}
