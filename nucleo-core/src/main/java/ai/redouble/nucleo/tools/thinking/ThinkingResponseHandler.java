/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.thinking;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.conversation.ContentBlocks.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.registry.*;
import org.slf4j.*;

import java.io.*;
import java.util.*;
import java.util.regex.*;

/**
 * ResponseHandler for ThinkingResponse that converts JSON into properly typed objects.
 *
 * <p>Handles two key conversions:
 * <ul>
 *   <li>ToolCall inputs from Maps to expected POJO types using ToolRegistry</li>
 *   <li>Answer field from Map to expected answer type. That is the only conversion performed,
 *       so an answer the declared type cannot hold - anything but an object for a composite
 *       type, anything the declared leaf type is not an instance of - is refused as a
 *       correctable LLM error. Whether the refusal reaches the model depends on the seat:
 *       {@link SingleObjectiveThinker} re-asks with the correction, while a
 *       {@link ReactiveThinker} exchange ends on it</li>
 * </ul>
 *
 * @param <O> the expected answer type
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-10-03)
 */
public class ThinkingResponseHandler<O> implements ResponseHandler<ThinkingResponse<O>> {
    private static final Logger log = LoggerFactory.getLogger(ThinkingResponseHandler.class);
    private final ToolRegistry toolRegistry;
    private final PojoResponseHandler<ThinkingResponse<O>> baseHandler;
    private final ResponseHandler<O> answerHandler;

    /**
     * Creates a handler that converts Maps to typed POJOs for both tool inputs and answers.
     *
     * @param toolRegistry   registry for looking up tool input types
     * @param baseHandler    handler for the outer ThinkingResponse structure
     * @param answerHandler1 handler for the answer field type
     * @throws IllegalArgumentException when no answer handler is given: this class cannot parse
     *         an answer without knowing its declared type, so absence is refused where it
     *         enters, at construction, naming the problem, instead of surfacing as a null
     *         dereference on the first model reply
     */
    public ThinkingResponseHandler(ToolRegistry toolRegistry, final PojoResponseHandler<ThinkingResponse<O>> baseHandler, final ResponseHandler<O> answerHandler1) {
        if (answerHandler1 == null) {
            throw new IllegalArgumentException("a ThinkingResponseHandler needs an answer handler: it declares the answer's type");
        }
        this.toolRegistry = toolRegistry;

        this.baseHandler = baseHandler;
        this.answerHandler = answerHandler1;
    }

    /**
     * Parses JSON and converts tool inputs and answer from Maps to typed POJOs.
     */
    @Override
    public ThinkingResponse<O> parse(String rawContent) throws IOException, ai.redouble.nucleo.harness.errors.LLMReadableCheckedException {
        ThinkingResponse<O> response = baseHandler.parse(rawContent);

        convertToolInputs(response);
        convertAnswer(response);
        identifyTextToolCalls(response);

        return response;
    }

    /**
     * A call parsed out of the model's text carries no provider id, and a result is only ever
     * recorded against an id: without one, {@code executeTools} would run the tool and drop
     * what it returned, and the model would answer from nothing. On a dialect with no native
     * tool channel the id is ours to mint, and it is minted from the tool's name and its place
     * in the turn, so the text-rendered result ({@code [Tool Result get_current_time#1] ...})
     * reads back to the model as the answer to the call it made.
     */
    private void identifyTextToolCalls(ThinkingResponse<O> response) {
        int ordinal = 0;
        for (ToolCall toolCall : response.getToolCalls()) {
            ordinal++;
            if (toolCall.getToolUseId() == null) {
                toolCall.setToolUseId(toolCall.getToolName() + "#" + ordinal);
            }
        }
    }

    /**
     * Parses structured ContentBlocks into a {@link ThinkingResponse}. Tool uses are
     * converted directly; text + thinking blocks flow through the reasoning precedence
     * in {@link #resolveReasoning}.
     *
     * <p>Precedence for the final {@code reasoning} field:
     * <ol>
     *   <li>Non-blank {@link ai.redouble.nucleo.harness.conversation.ContentBlocks.ThinkingBlock} text
     *       (Anthropic's first-class reasoning channel).</li>
     *   <li>Top-level {@code reasoning} field inside a JSON envelope in the text blocks.</li>
     *   <li>Prose surrounding the JSON envelope, if present.</li>
     *   <li>Full text block contents, when no JSON is found.</li>
     *   <li>Default empty {@link ai.redouble.nucleo.harness.schema.SimpleReasoning} - no reasoning was produced.</li>
     * </ol>
     */
    @Override
    public ThinkingResponse<O> parse(List<ContentBlock> contentBlocks) throws IOException, ai.redouble.nucleo.harness.errors.LLMReadableCheckedException {
        ThinkingResponse<O> response = new ThinkingResponse<>();
        StringBuilder textContent = new StringBuilder();
        StringBuilder thinkingContent = new StringBuilder();

        for (ContentBlock block : contentBlocks) {
            if (block instanceof ToolUseBlock toolUse) {
                // Every native tool_use block MUST yield exactly one ToolCall. Anthropic requires
                // each tool_use in the assistant turn to be answered by a tool_result in the next
                // user turn; if we drop a block here (by throwing on an unregistered tool or an
                // unparseable input) the whole batch is abandoned, the already-recorded assistant
                // tool_use turn is orphaned, and the next request 400s. So an unresolvable call is
                // kept with its raw input - executeTools then rejects it (createTool throws
                // "Unknown tool" for an unregistered name, or the input-type check rejects a raw
                // node) and emits an LLM-readable error result, keeping the pairing intact.
                ToolCall toolCall = new ToolCall();
                toolCall.setToolName(toolUse.toolName());
                toolCall.setToolUseId(toolUse.toolUseId());
                com.fasterxml.jackson.databind.JsonNode raw =
                        NucleoJsonSerializer.readTree(toolUse.inputJson());
                ToolProvider provider = toolRegistry.getProviderByName(toolUse.toolName());
                Object input = raw;
                if (provider != null) {
                    try {
                        input = provider.parseInput(raw);
                    }
                    catch (ai.redouble.nucleo.harness.errors.CorrectableLLMException e) {
                        // Registered tool whose input could not be parsed. Keep the call so its
                        // tool_use is still answered, and carry the correctable reason on the ToolCall
                        // so submitToolCall surfaces it as the tool_result - the model is told exactly
                        // what was wrong (valid options / loader redirect) and can fix its call, rather
                        // than getting the raw node type-rejected as an opaque SystemException.
                        log.warn("Could not parse input for tool {}; keeping the call so its tool_use is answered: {}", toolUse.toolName(), e.getLLMMessage());
                        toolCall.setParseError(e);
                    }
                }
                toolCall.setInput(input);
                response.getToolCalls().add(toolCall);
            }
            else if (block instanceof TextBlock textBlock) {
                textContent.append(textBlock.text());
            }
            else if (block instanceof ai.redouble.nucleo.harness.conversation.ContentBlocks.ThinkingBlock tb) {
                if (tb.text() != null && !tb.text().isEmpty()) {
                    thinkingContent.append(tb.text());
                }
            }
            // Other block types (Redacted thinking, tool definitions, etc.) don't
            // contribute to the ThinkingResponse payload.
        }

        if (!textContent.isEmpty()) {
            try {
                ThinkingResponse<O> parsedResponse = baseHandler.parse(textContent.toString());
                response.setFinalAnswer(parsedResponse.isFinalAnswer());
                response.setAnswer(parsedResponse.getAnswer());
                convertAnswer(response);
                if (parsedResponse.getToolCalls() != null && !parsedResponse.getToolCalls().isEmpty()) {
                    response.getToolCalls().addAll(parsedResponse.getToolCalls());
                    convertToolInputs(response);
                }
                response.setReasoning(resolveReasoning(thinkingContent.toString(), textContent.toString(), parsedResponse.getReasoning()));
            }
            catch (IOException e) {
                if (response.getToolCalls().isEmpty() && thinkingContent.isEmpty()) {
                    throw e; // No tool calls, no thinking - text must be valid JSON
                }
                response.setReasoning(resolveReasoning(thinkingContent.toString(), textContent.toString(), null));
            }
        }
        else if (!thinkingContent.isEmpty()) {
            response.setReasoning(resolveReasoning(thinkingContent.toString(), "", null));
            response.setFinalAnswer(response.getToolCalls().isEmpty());
        }
        else {
            response.setFinalAnswer(response.getToolCalls().isEmpty());
        }

        // Safety: if tool calls are present, this is not a final answer regardless of text
        if (!response.getToolCalls().isEmpty()) {
            response.setFinalAnswer(false);
        }

        return response;
    }

    /**
     * What the schema accepts for a field the decoder refused, composed from the declared type
     * the decoder carries rather than from the decoder's complaint: the complaint quotes the
     * value it rejected, and a model told only that its value was wrong cannot fix it, while a
     * model told what is accepted can. An enum names its accepted values, which the schema
     * published; any other carried type names itself. Only a decoder that carries no target
     * type still forwards its complaint, and every deserializer this framework registers
     * carries one.
     */
    private static String ruleFor(com.fasterxml.jackson.databind.JsonMappingException jme) {
        if (jme instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException invalid && invalid.getTargetType() != null) {
            if (invalid.getTargetType().isEnum()) {
                return "must be one of " + String.join(", ", PojoResponseHandler.enumWireValues(invalid.getTargetType()));
            }
            return "must be a value the declared type " + invalid.getTargetType().getSimpleName() + " can hold";
        }
        return jme.getOriginalMessage();
    }

    /**
     * Applies the reasoning precedence described on {@link #parse(List)}.
     *
     * @param thinkingText concatenated text from ThinkingBlocks, may be empty
     * @param rawText concatenated text from TextBlocks, may be empty
     * @param parsedReasoning reasoning extracted from the JSON envelope, may be null
     * @return the reasoning to attach to the response
     */
    private ai.redouble.nucleo.harness.schema.SimpleReasoning resolveReasoning(String thinkingText, String rawText, ai.redouble.nucleo.harness.schema.SimpleReasoning parsedReasoning) {
        if (thinkingText != null && !thinkingText.isEmpty()) {
            ai.redouble.nucleo.harness.schema.SimpleReasoning sr = new ai.redouble.nucleo.harness.schema.SimpleReasoning();
            sr.setThought(thinkingText);
            return sr;
        }
        if (parsedReasoning != null && parsedReasoning.getThought() != null && !parsedReasoning.getThought().isEmpty()) {
            return parsedReasoning;
        }
        if (rawText == null || rawText.isEmpty()) {
            return new ai.redouble.nucleo.harness.schema.SimpleReasoning();
        }
        // No thinking block and no top-level reasoning. Use the prose around the JSON if any; when
        // the text is pure JSON (no surrounding prose) leave reasoning empty rather than surface the
        // raw JSON envelope as the thought - a user-facing reasoning notification must not show JSON.
        NucleoJsonSerializer.JsonProseSplit split = NucleoJsonSerializer.extractJsonWithProse(rawText);
        ai.redouble.nucleo.harness.schema.SimpleReasoning sr = new ai.redouble.nucleo.harness.schema.SimpleReasoning();
        if (!split.prose().isEmpty()) {
            sr.setThought(split.prose());
        }
        return sr;
    }

    /**
     * Converts tool call inputs from Maps (legacy non-native path) to their typed inputs
     * by routing through the registered {@link ToolProvider#parseInput(com.fasterxml.jackson.databind.JsonNode)}.
     * The Map is converted to a {@link com.fasterxml.jackson.databind.JsonNode} once before
     * the provider sees it.
     */
    private void convertToolInputs(ThinkingResponse<O> response) throws IOException, ai.redouble.nucleo.harness.errors.LLMReadableCheckedException {
        if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
            return;
        }
        for (ToolCall toolCall : response.getToolCalls()) {
            Object rawInput = toolCall.getInput();
            if (rawInput instanceof Map) {
                ToolProvider provider = toolRegistry.getProviderByName(toolCall.getToolName());
                if (provider == null) {
                    throw new ai.redouble.nucleo.harness.errors.InvalidInputException(
                            "tool", toolCall.getToolName(), "tool not registered");
                }
                com.fasterxml.jackson.databind.JsonNode raw =
                        NucleoJsonSerializer.valueToTree(rawInput);
                toolCall.setInput(provider.parseInput(raw));
            }
        }
    }

    /**
     * Converts answer from Map to expected POJO type if applicable.
     * Also ensures artifact references mentioned in text are included in artifact_refs.
     *
     * <p>A Jackson type-mismatch (e.g. an enum field receiving a JSON object) is wrapped as a
     * correctable {@link ai.redouble.nucleo.harness.errors.InvalidInputException} so the agent loop can feed the
     * error back to the LLM rather than failing the job. This is distinct from a JSON parse
     * failure on the surrounding text, which is still an {@link IOException}.
     */
    @SuppressWarnings("unchecked")
    private void convertAnswer(ThinkingResponse<O> response) throws IOException, ai.redouble.nucleo.harness.errors.LLMReadableCheckedException {
        Object rawAnswer = response.getAnswer();
        Class<?> answerType = answerHandler.getResponseClass();
        if (rawAnswer instanceof Map && NucleoJsonSerializer.isComposite(answerType)) {
            O convertedAnswer;
            try {
                convertedAnswer = (O)convertMapToPojo((Map<String, Object>)rawAnswer, answerType);
            }
            catch (IOException e) {
                // NucleoJsonSerializer.parse wraps Jackson's JsonProcessingException as a plain
                // IOException, so catching JsonMappingException here would never fire. Inspect
                // the cause to surface schema mismatches as correctable LLM errors. Other
                // IOException causes (genuine I/O, etc.) are unexpected here and rethrown.
                if (e.getCause() instanceof com.fasterxml.jackson.databind.JsonMappingException jme) {
                    throw new ai.redouble.nucleo.harness.errors.InvalidInputException(jsonPath(jme), "", ruleFor(jme), jme);
                }
                throw e;
            }
            response.setAnswer(convertedAnswer);
        }
        // A null answer is deliberately not judged: an absent or explicitly null answer means
        // the model has not answered yet, which is the loop's business and no shape violation.
        else if (rawAnswer != null && !answerType.isInstance(rawAnswer)) {
            // An answer of any shape the declared type cannot hold. The branch above is the one
            // conversion this handler performs (a JSON object into a composite answer type);
            // there is no other, so anything left that is not already an instance of the
            // declared type cannot become one. It is refused rather than passed on because the
            // thinking loop assigns it through an erased cast: a wrong shape reaching that cast
            // is a ClassCastException inside a SystemException, which ends the run and tells the
            // model nothing, where a correctable refusal lets the loop re-ask.
            // The refusal names the type the schema declared, never the value the model sent.
            String accepted = NucleoJsonSerializer.isComposite(answerType)
                    ? "must be a JSON object matching the answer schema"
                    : "must be a single JSON value the declared type " + answerType.getSimpleName() + " can hold";
            throw new ai.redouble.nucleo.harness.errors.InvalidInputException("answer", "",
                    accepted + ", not a " + rawAnswer.getClass().getSimpleName());
        }
        // The canonicalization gate: the declared refs and the text-mentioned ones become one
        // canonical, deduplicated list, so a response leaving the loop never carries a variant
        // spelling and a text-mentioned artifact cannot hide behind a declared duplicate
        if (response.getAnswer() instanceof ArtifactResponse<?> artifactResponse) {
            artifactResponse.canonicalizeArtifactRefs(response.getAnswer().toString());
        }
    }

    /**
     * Converts a Map to a POJO by serializing to JSON and parsing with the appropriate handler.
     */
    @SuppressWarnings("unchecked")
    private <T> T convertMapToPojo(Map<String, Object> map, Class<T> targetClass) throws IOException {
        String json = NucleoJsonSerializer.write(map);
        return NucleoJsonSerializer.parse(json, targetClass);
    }

    /**
     * Renders Jackson's mapping path as a dotted/indexed JSON path (e.g.
     * {@code response_type} or {@code key_findings[2]}). Falls back to {@code "answer"}
     * when the path is empty - InvalidInputException requires a non-null parameter name.
     */
    private static String jsonPath(com.fasterxml.jackson.databind.JsonMappingException jme) {
        StringBuilder sb = new StringBuilder();
        for (com.fasterxml.jackson.databind.JsonMappingException.Reference ref : jme.getPath()) {
            if (ref.getFieldName() != null) {
                if (!sb.isEmpty()) sb.append('.');
                sb.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return !sb.isEmpty() ? sb.toString() : "answer";
    }

    @Override
    public String write(ThinkingResponse<O> response) {
        return baseHandler.write(response);
    }

    /**
     * The envelope's own required fields, plus the answer's when an answer is present. The
     * answer carries its own schema, required fields included, and that schema is published to
     * the model inside this envelope's; validating only the envelope would publish
     * {@code (REQUIRED)} on the answer's fields and never check them, so an answer carrying
     * none of them would arrive as an all-null object indistinguishable from a real one. A turn
     * that calls a tool has no answer and is judged on the envelope alone.
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<String> getValidationErrors(ThinkingResponse<O> response) {
        List<String> errors = new ArrayList<>(baseHandler.getValidationErrors(response));
        Object answer = response == null ? null : response.getAnswer();
        if (answer != null) {
            errors.addAll(answerHandler.getValidationErrors((O)answer));
        }
        return errors;
    }

    /**
     * The answer's artifacts and each tool call's are taken from the registry. A violation
     * in the answer is the reply's, reported with its other validation errors. A violation
     * in a tool call's input is that call's: the call is kept and carries the refusal, so
     * its tool_use is answered with the reason and the model can correct that one call.
     */
    @Override
    public ThinkingResponse<O> heldArtifacts(ThinkingResponse<O> parsed, ArtifactRegistry registry, List<String> violations) {
        if (parsed == null) {
            return null;
        }
        parsed.setAnswer(registry.held(parsed.getAnswer(), violations));
        if (parsed.getToolCalls() != null) {
            for (ToolCall toolCall : parsed.getToolCalls()) {
                if (toolCall.getParseError() != null) {
                    continue;
                }
                List<String> refused = new ArrayList<>();
                Object input = registry.held(toolCall.getInput(), refused);
                if (refused.isEmpty()) {
                    toolCall.setInput(input);
                }
                else {
                    toolCall.setParseError(new ai.redouble.nucleo.harness.errors.InvalidInputException("input", "", String.join("; ", refused)));
                }
            }
        }
        return parsed;
    }

    @Override
    public PojoDefinition writeDefinition() {
        PojoDefinition baseDefinition = baseHandler.writeDefinition();
        if (baseDefinition.getFields().containsKey("answer")) {
            PojoDefinition answerDef = answerHandler.writeDefinition();
            FieldDescriptor answerField = baseDefinition.getFields().get("answer");
            if (answerField != null) {
                answerField.setDefinition(answerDef);
                answerField.setType("object");
            }
        }
        return baseDefinition;
    }

    // Both name the shape - one JSON object matching the schema - because "valid JSON" is also
    // satisfied by a bare string, which a model told elsewhere to answer tersely will produce
    private static final String JSON_ONLY_PREAMBLE =
            "\n\nRespond with one JSON object only, matching the schema below, without markdown code blocks, explanations, or any other text."
            + " The response should be that parseable JSON object with no additional formatting or text.\n";
    private static final String NATIVE_TOOLS_PREAMBLE =
            "\n\nWhen you need more information, use the available tools. When you have enough information to provide a final answer,"
            + " respond with one JSON object only, matching the schema below, without markdown code blocks or any other text.\n";

    @Override
    public boolean usesSchemaNotation() {
        return true;
    }

    @Override
    public String responseInstructions() {
        return JSON_ONLY_PREAMBLE + this.writeDefinition().toLLMSchema();
    }

    @Override
    public String responseInstructions(boolean nativeToolsAvailable, boolean thinkingActive) {
        boolean nativeTools = nativeToolsAvailable && !toolRegistry.getAllProviders().isEmpty();
        if (!nativeTools && !thinkingActive) {
            return responseInstructions();
        }
        PojoDefinition def = writeDefinition();
        if (nativeTools) {
            // Native tool calling: the model emits tool_use blocks, so tool_calls is not in the schema.
            def.getFields().remove("tool_calls");
        }
        if (thinkingActive) {
            // The native thinking block carries the envelope's reasoning, so omit the prose
            // reasoning field rather than spend output tokens on a value resolveReasoning discards
            // - the envelope's own and the answer type's, which a ReasonablePojo answer carries
            // nested. The strip is about the redundant output alone: a reasoning-extraction
            // classifier keys on the wording of a field, thinking block or not (Opus 5.5 refused a
            // field described as the model's "thought process" on every call without thinking and
            // on half of them with it, and passed the same field reworded on all of them), which is
            // why the descriptions ask for a justification, never for a thought process.
            def.getFields().remove("reasoning");
            FieldDescriptor answer = def.getFields().get("answer");
            if (answer != null && answer.getDefinition() != null) {
                answer.getDefinition().getFields().remove("reasoning");
            }
        }
        return (nativeTools ? NATIVE_TOOLS_PREAMBLE : JSON_ONLY_PREAMBLE) + def.toLLMSchema();
    }

    @Override
    public Class<ThinkingResponse<O>> getResponseClass() {
        return baseHandler.getResponseClass();
    }
}