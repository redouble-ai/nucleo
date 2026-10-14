/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.tokens;

/**
 * The parts of a rendered conversation an estimate accounts separately, so a refusal can
 * say which part filled the window. Together they are the whole input.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-10-09)
 */
public enum ContextPart {
    /** The system content: the prompt and input object, the legend and the tool guidance. */
    OBJECTIVE,
    /** The tool definitions in the request's palette, each counted in its text form; a definition announced in a turn after the head froze is a message. */
    TOOL_DEFINITIONS,
    /** The conversation turns: user, assistant, tool calls and tool results. */
    MESSAGES,
    /** The artifact registry section appended after the turns. */
    ARTIFACT_REGISTRY
}
