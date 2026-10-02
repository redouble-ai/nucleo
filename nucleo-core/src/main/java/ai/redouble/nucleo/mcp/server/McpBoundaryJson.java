/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.*;
import io.modelcontextprotocol.json.*;
import io.modelcontextprotocol.json.jackson2.*;

/**
 * The JSON reader a host must give its transport, so that bytes from outside are decoded as
 * strictly as {@link McpInputGate} judges what they decoded into.
 *
 * <p>The gate never sees bytes. By the time it runs, a document has been parsed into objects
 * and every parser decision has already been taken: which of two duplicate keys survived,
 * whether a comment was skipped, whether trailing bytes after the document were ignored.
 * Those are decided here or not at all, and the defaults decide several of them the wrong
 * way for a boundary. Duplicate keys are the sharp one: Jackson keeps the last silently, so
 * {@code {"amount":1,"amount":9999}} reaches the gate as a single well-formed field and
 * passes every check it has.
 *
 * <p>Everything below is pinned explicitly, including the features whose default already
 * matches. A default is not a decision, and a boundary should not change behaviour because
 * a library changed its mind.
 *
 * <h2>Wiring</h2>
 * <pre>
 * HttpServletStatelessServerTransport.builder()
 *         .jsonMapper(McpBoundaryJson.strictMapper())
 *         ...
 * </pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public final class McpBoundaryJson {

    private McpBoundaryJson() {
    }

    /**
     * A reader that refuses a document a stranger should not have sent: duplicate keys,
     * anything after the document, comments, trailing commas, single quotes, unquoted names,
     * leading zeros, control characters in strings, and the non-numbers {@code NaN} and
     * {@code Infinity}.
     */
    public static McpJsonMapper strictMapper() {
        return new JacksonMcpJsonMapper(strictObjectMapper());
    }

    /**
     * The request path's tree converter: what the SDK already decoded, as a tree the gate
     * judges. A boundary mapper because the in-process serializer drops null map values
     * on conversion, which would spell a caller's explicit null as an omitted key before
     * the gate saw it. Configured once and only read afterwards, so one instance serves
     * every request.
     */
    static final ObjectMapper TREE_MAPPER = strictObjectMapper();

    /**
     * The mapper behind {@link #strictMapper()}, for a host that needs the Jackson type.
     */
    public static ObjectMapper strictObjectMapper() {
        return JsonMapper.builder()
                // The one the default gets wrong: without this the last of two duplicate keys
                // wins and nothing downstream can tell there were two.
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                // A document followed by more bytes is two documents, and reading one is a guess.
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS,
                        JsonReadFeature.ALLOW_YAML_COMMENTS,
                        JsonReadFeature.ALLOW_TRAILING_COMMA,
                        JsonReadFeature.ALLOW_SINGLE_QUOTES,
                        JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES,
                        JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS,
                        JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS,
                        JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS,
                        JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
                // An undeclared property is the gate's refusal to make, with a message that
                // names what IS accepted; failing here would answer a parser's message instead.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
}
