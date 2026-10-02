/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.registry.*;

import java.time.*;
import java.util.*;

/**
 * Interface for reasoning agents that coordinate tool execution.
 *
 * <p>Thinkers are specialized tools that solve problems by iteratively using
 * available tools. They accept structured input like all tools, but their execution
 * involves complex multi-step reasoning and tool orchestration.
 *
 * <p><b>Key difference from simple tools:</b> Thinkers run conversation loops with LLMs
 * and dynamically select which tools to use, while simple tools perform single operations.
 *
 * <p><b>Composition:</b> Since thinkers are tools, they can be used by other thinkers,
 * enabling hierarchical multi-agent systems where meta-agents coordinate specialist agents.
 *
 * @param <I> Input type - must extend ThinkerInput for uniform query handling
 * @param <O> Output type - must extend ThinkerOutput for uniform response handling
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-09-19)
 */
public interface Thinker<I extends ThinkerInput, O extends ThinkerOutput<? extends Reasoning>> extends Orchestrator<I, O>, ModelDependentTool<I, O>, ConversationCarrier {

    /** Whether a human is waiting on this thinker's answers - a demand flavor pickers may weigh. */
    boolean isInteractive();

    /**
     * How much effort this thinker's calls ask for: its input's depth for a goal-directed
     * thinker, its declared field for a reactive one. Stamped onto the conversation by
     * ConversationService at obtain time, next to the grade.
     */
    Depth getDepth();

    /**
     * How much answer this thinker's turns book: an {@link OutputSize} rung or a raw count,
     * declared in the constructor next to the grade. Null before the declaration; execution
     * refuses a thinker that reaches it without one. Stamped onto the conversation by
     * ConversationService at obtain time.
     */
    OutputDeclaration getOutputDeclaration();

    // ================ Tool Management ================

    /**
     * Adds a tool to this thinker's available tools.
     * The tool will be registered and can be invoked by the LLM.
     *
     * @param toolClass the tool class to add
     */
    void addTool(Class<? extends Tool> toolClass);

    /**
     * Adds a {@link ToolProvider} to this thinker's available tools. Mirror of
     * {@link #addTool(Class)} for providers that are not class-backed (e.g. MCP-bound).
     *
     * @param provider the provider to add
     */
    void addTool(ToolProvider provider);

    /**
     * Removes a tool from this thinker's available tools.
     *
     * @param toolClass the tool class to remove
     */
    void removeTool(Class<? extends Tool> toolClass);

    /**
     * Removes a tool by registered name. Mirror of {@link #removeTool(Class)} for
     * providers without an associated Java class.
     *
     * @param toolName the tool name to remove
     */
    void removeTool(String toolName);

    /**
     * Checks if a tool class is registered with this thinker.
     *
     * @param toolClass the tool class to check
     * @return true if the tool is registered
     */
    boolean hasTool(Class<? extends Tool> toolClass);

    /**
     * Checks if a tool name is registered with this thinker.
     *
     * @param toolName the tool name to check
     * @return true if the tool is registered
     */
    boolean hasTool(String toolName);

    /**
     * Gets all currently registered tool classes.
     *
     * @return unmodifiable collection of tool classes
     */
    Collection<Class<? extends Tool>> getTools();

    /**
     * Gets all currently registered providers (class-based and otherwise).
     *
     * @return unmodifiable collection of providers
     */
    Collection<ToolProvider> getProviders();
    /**
     * Builds tool definition blocks for all registered tools.
     * Used for conversation context when loading from persistence.
     *
     * @return list of tool definition blocks
     */
    List<ToolDefinitionBlock> buildToolDefinitionBlocks();

    // ================ Message Caching ================

    /**
     * Returns whether all conversation messages should be cached.
     * When enabled, every message added to the conversation context will have
     * caching enabled automatically (if supported by the LLM provider).
     *
     * @return true to cache all messages, false for manual control
     */
    boolean cacheAllMessages();

    /**
     * Sets whether all conversation messages should be cached.
     *
     * @param cacheAllMessages true to enable caching for all messages
     */
    void setCacheAllMessages(boolean cacheAllMessages);

    // ================ Tool Invocation Control ================

    /**
     * Returns whether this thinker was invoked as a tool by another thinker.
     * When true, the thinker should not stream results directly to the user -
     * the results will be returned to the parent thinker as a tool result.
     *
     * @return true if invoked as a tool, false if running standalone
     */
    boolean isInvokedAsTool();

    /**
     * Marks this thinker as being invoked as a tool.
     * Set automatically by the parent thinker when using this thinker as a tool.
     *
     * @param invokedAsTool true if being used as a tool
     */
    void setInvokedAsTool(boolean invokedAsTool);

    // ================ Memory Retention ================

    /**
     * Gets the retention duration for intermediate releases (between tool calls).
     * Controls how long the conversation stays in memory during active processing.
     *
     * @return the intermediate retention duration
     */
    Duration getRetainDuration();

    /**
     * Sets the retention duration for intermediate releases.
     *
     * @param retainDuration the intermediate retention duration
     */
    void setRetainDuration(Duration retainDuration);

    /**
     * Gets the retention duration after the thinker finishes (execute()'s finally block).
     * Controls how long the conversation persists in memory after the thinker completes.
     *
     * @return the final retention duration
     */
    Duration getFinalRetainDuration();

    /**
     * Sets the retention duration after the thinker finishes.
     *
     * @param finalRetainDuration the final retention duration
     */
    void setFinalRetainDuration(Duration finalRetainDuration);

    // ================ Conversation Management ================

    /**
     * Gets the conversation ID for this thinker.
     * By default returns the job ID for new conversations.
     * Can be set to resume existing conversations.
     *
     * @return the conversation ID (job ID if not explicitly set)
     */
    String getConversationId();

    /**
     * Adopts an existing conversation: the thinker resumes the conversation with this id,
     * and obtaining it fails when no store holds it - a resume that silently minted an
     * empty conversation would leave the model defending reasoning it can no longer see.
     *
     * @param conversationId the existing conversation ID to resume
     */
    void setConversationId(String conversationId);

    /**
     * Mints a durable identity for a brand-new conversation, decoupled from job lineage.
     * The id follows the framework's readable shape - the thinker's class name, a
     * {@code conv} marker, and a short random tail (e.g.
     * {@code ChatThinker-conv-a26f-52b01fa3bd75}) - so DB rows and logs stay
     * scannable and a minted id is never mistaken for a job id. The conversation is
     * created fresh under this id and every later turn adopts it via
     * {@link #setConversationId(String)}. Thinkers that call neither keep the default:
     * a temporary conversation keyed by the thinker's own job id.
     *
     * @return the minted conversation ID
     */
    String mintConversationId();

    /**
     * Whether this thinker's conversation identity was minted fresh (as opposed to
     * adopted or left on the temporary jobId default). A minted conversation has no
     * durable record yet: its first user-message save CREATES the record - titled and
     * embedded from that message, exactly once - and every later save is an update.
     *
     * @return true when {@link #mintConversationId()} named this conversation
     */
    boolean isConversationMinted();
}