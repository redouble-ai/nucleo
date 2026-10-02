/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import ai.redouble.nucleo.harness.errors.*;

/**
 * What a failure is allowed to say to a caller in another process.
 *
 * <p>In process, {@code getLLMMessage()} is written for our own model and quotes freely -
 * the offending value, the parser's complaint - because that is how a model corrects
 * itself, and the text goes nowhere but its own context. Across the boundary the same text
 * is a reflection of whatever a caller sent, back to whoever is reading. A consumer that
 * puts a credential in the wrong field should not find it in an error string, in a log, or
 * in the context of an agent that happens to be relaying the call.
 *
 * <p>So the boundary renders from what THIS process knows - the exception's kind, the
 * declared parameter, the resource type - and never from the free text of an exception it
 * did not author. {@link McpInputGate} refusals are the exception that proves it: they are
 * rendered whole precisely because the gate composes them from our own schema, and the
 * handler renders them at the gate's own call site rather than through here.
 *
 * <p>The rule is verified rather than trusted: {@code McpHostileInputTest} replays a corpus
 * of malformed and hostile payloads and asserts no distinctive fragment of any of them
 * appears in the response.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public final class McpErrorText {

    private McpErrorText() {
    }

    /**
     * The caller-facing text for a failure the boundary did not author.
     *
     * @param readable the failure, already unwrapped
     * @return a message that names the shape of the problem and quotes nothing
     */
    public static String of(LLMReadable readable) {
        if (readable instanceof InvalidInputException invalid) {
            // The parameter and the rule are both composed by their author from the declared
            // contract - the gate from the schema, a tool from what it accepts, the parser
            // from the type it had to fit (ClassToolProvider.parseInput logs the parser's
            // complaint and names only the field path and its declared type). What is never
            // rendered is the invalid VALUE, which is the caller's own text.
            return "Parameter '" + invalid.getParameterName() + "' was rejected: " + invalid.getValidationRule();
        }
        if (readable instanceof ResourceNotFoundException notFound) {
            // The identifier is the caller's own text; it knows what it asked for.
            return notFound.getResourceType() + " was not found.";
        }
        // Everything else - guardrail refusals, permission denials, upstream failures,
        // system errors - carries text this process authored as a constant.
        return readable.getLLMMessage();
    }
}
