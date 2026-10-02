/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.bedrock;

import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.bedrockruntime.model.*;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization of the wire request {@link BedrockConverseClient} builds from a prepared
 * conversation. The channel contract: the main objective is the conversation's only system
 * content and reaches the wire exactly once, in the system channel; turns keep their roles.
 *
 * <p>This client previously made its own routing decision - it copied the objective into
 * system by reading the raw conversation, while the shared step had already placed it in a
 * user turn, so the objective shipped twice. It now renders what it is handed and decides
 * nothing; the total-count assertions here are what pin that.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-23)
 */
public class BedrockConverseRequestShapeTest {

    private static final String OBJECTIVE = "OBJECTIVE-MARKER extract the port labels";
    private static final String USER_TURN = "USER-MARKER Ringports changed to 5 and 7.";
    private static final String INJECTED_TURN = "INJECTED-MARKER also check port 9.";
    private static final String ASSISTANT_TURN = "ASSISTANT-MARKER previous answer";
    private static final String TOOL_NAME = "TOOL-MARKER-probe_tool";

    @Test
    void objectiveReachesTheWireExactlyOnce_inSystem() {
        ConverseRequest request = build();

        assertEquals(1, countInSystem(request.system(), OBJECTIVE),
                "the objective is the system content");
        assertEquals(0, countInTurns(request.messages(), OBJECTIVE),
                "and is not also a turn - shipping it through both channels was the defect"
                        + " this contract exists to prevent");
    }

    @Test
    void userAndAssistantRolesArePreserved() {
        ConverseRequest request = build();

        assertEquals(ConversationRole.USER, roleOfTurnContaining(request, USER_TURN),
                "a user turn stays a user turn");
        assertEquals(ConversationRole.ASSISTANT, roleOfTurnContaining(request, ASSISTANT_TURN),
                "an assistant turn stays an assistant turn");
    }

    @Test
    void maxTokensIsTheResolvedOutputBudget() {
        ModelSpec model = TestModels.micro();
        ConverseRequest request = build();

        assertEquals(model.getMaxOutputTokens(), request.inferenceConfig().maxTokens(),
                "the wire ceiling is the budget the framework resolved, capped at the model's own"
                        + " maximum - the micro tier's ceiling sits below the project default");
    }

    @Test
    void modelIdIsTheSpecsWireId() {
        ModelSpec model = TestModels.micro();

        assertEquals(model.getWireModelId(), build().modelId(),
                "Bedrock is addressed by the spec's wire id, not by the catalog id");
    }

    @Test
    void initialPaletteReachesTheWireExactlyOnce_afterTheInstructions() {
        ConverseRequest request = build();

        assertEquals(1, countInSystem(request.system(), TOOL_NAME),
                "the initial palette renders once, in the stable head after the instructions -"
                        + " deliberately as text, never toolConfig: the native parameter switches"
                        + " Nova into Amazon's tool-use scaffolding and it stops answering in the"
                        + " response envelope (verified live)");
        assertEquals(0, countInTurns(request.messages(), TOOL_NAME),
                "and not also as a turn");
        assertTrue(request.toolConfig() == null,
                "no native toolConfig on this client");
    }

    @Test
    void consecutiveUserTurnsReachTheWireUncoalescedAndInOrder() {
        // the injection wire shape: a follow-up steered into a running exchange rides as
        // its own user turn directly after the previous user turn; the encoder must
        // neither merge nor reorder the pair - provider acceptance is gated by the live probe
        ConverseRequest request = build(conversation ->
                conversation.getMessages().add(message("user", INJECTED_TURN)));

        int first = indexOfTurnContaining(request, USER_TURN);
        int second = indexOfTurnContaining(request, INJECTED_TURN);
        assertEquals(first + 1, second, "the injected turn directly follows the triggering user turn");
        assertEquals(ConversationRole.USER, request.messages().get(first).role());
        assertEquals(ConversationRole.USER, request.messages().get(second).role());
        assertEquals(1, countInTurns(request.messages(), INJECTED_TURN),
                "carried exactly once, never merged into another turn");
    }

    /** One canonical conversation: a main objective, a tool palette, an assistant turn, a user turn. */
    @Test
    void temperatureTravelsOnlyWhenACallerSetOne() {
        assertNull(build().inferenceConfig().temperature(),
                "unset sends nothing: each model's own vendor default applies, and models that accept no sampling parameter stay callable");
        assertEquals(0.2f, build(conversation -> { }, 0.2).inferenceConfig().temperature(),
                "a caller-set temperature reaches the inference config, and the provider's verdict on it is the caller's to receive");
    }

    private ConverseRequest build() {
        return build(conversation -> { }, null);
    }

    private ConverseRequest build(java.util.function.Consumer<ConversationContext> extraTurns) {
        return build(extraTurns, null);
    }

    private ConverseRequest build(java.util.function.Consumer<ConversationContext> extraTurns, Double temperature) {
        ModelSpec model = TestModels.micro();
        ConversationContext conversation = TestModels.conversation(model);
        // a declaration above the micro tier's ceiling: the wire must clamp it to what the model can emit
        conversation.setDepth(Depth.IMMEDIATE);
        conversation.setOutputDeclaration(OutputDeclaration.of(model.getMaxOutputTokens() + 6_000));
        conversation.putMainObjective("task", OBJECTIVE);
        conversation.addTool(new ai.redouble.nucleo.harness.conversation.ContentBlocks.ToolDefinitionBlock(
                TOOL_NAME, "finds things", "{\"type\":\"object\"}"));
        conversation.getMessages().add(message("assistant", ASSISTANT_TURN));
        conversation.getMessages().add(message("user", USER_TURN));
        extraTurns.accept(conversation);
        BedrockConverseClient client = new BedrockConverseClient();
        client.setModel(model);
        client.setTemperature(temperature);
        LLMRequest<String> request = new LLMRequest<>(conversation);
        PreparedConversation prepared = LlmTestDoors.prepare(client, conversation);
        return client.buildConverseRequest(request, prepared);
    }

    private static int indexOfTurnContaining(ConverseRequest request, String marker) {
        for (int i = 0; i < request.messages().size(); i++) {
            if (request.messages().get(i).toString().contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("no turn carries " + marker);
    }

    private static OutgoingMessage<String> message(String role, String text) {
        OutgoingMessage<String> message = new OutgoingMessage<>(StringResponseHandler.instance);
        message.setRole(role);
        message.addText(text);
        return message;
    }

    private static int countInSystem(List<SystemContentBlock> blocks, String marker) {
        int found = 0;
        for (SystemContentBlock block : blocks) {
            if (block.text() != null && block.text().contains(marker)) {
                found++;
            }
        }
        return found;
    }

    private static int countInTurns(List<Message> messages, String marker) {
        int found = 0;
        for (Message message : messages) {
            for (ContentBlock block : message.content()) {
                if (block.text() != null && block.text().contains(marker)) {
                    found++;
                }
            }
        }
        return found;
    }

    private static ConversationRole roleOfTurnContaining(ConverseRequest request, String marker) {
        for (Message message : request.messages()) {
            for (ContentBlock block : message.content()) {
                if (block.text() != null && block.text().contains(marker)) {
                    return message.role();
                }
            }
        }
        throw new AssertionError("no turn carries " + marker);
    }
}
