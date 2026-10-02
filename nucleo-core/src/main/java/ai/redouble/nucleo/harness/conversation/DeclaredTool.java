/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;


/**
 * A tool declared to a conversation before it has been rendered into a wire definition.
 *
 * <p>{@link ConversationContext} holds declared tools by {@link #name()} and calls
 * {@link #definition()} exactly once, at the first render after the declaration - never
 * eagerly and never again after. That single conversion point lets implementations defer
 * expensive work (schema generation) until the definition is actually needed, and it makes
 * pre-render churn free: a tool declared and never rendered costs nothing.
 *
 * <p>Declarations are add-only and definitions are immutable once converted: re-declaring
 * a name that is already declared is a no-op, whatever the new source would have produced.
 * A {@link ToolDefinitionBlock} is its own {@code DeclaredTool}, so already-converted
 * definitions (restored snapshots, pre-built palettes) declare directly.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-24)
 */
public interface DeclaredTool {

    /** The tool's unique name; the identity under which the declaration is held. */
    String name();

    /** Converts this declaration into its wire definition. Called once, at first render after declaration. */
    ToolDefinitionBlock definition();
}
