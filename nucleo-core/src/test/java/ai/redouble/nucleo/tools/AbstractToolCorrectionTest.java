/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.llm.encode.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.harness.schema.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A one-call tool built on {@code wireConversation} corrects the model the way
 * {@code LLMCall} does: an answer that does not parse, or parses without its required
 * fields, goes back as a correction turn under the shared budget, and the retry signal
 * survives the broad catch every tool wraps its exchange in. Before this, a tool whose
 * model wrote one stray character in its JSON failed outright, with no second chance.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
public class AbstractToolCorrectionTest {

    /** Typed answer with a required field, so an empty object fails validation. */
    public static class StrictAnswer {
        @LLMRequired
        private String verdict;

        public String getVerdict() {return verdict;}

        public void setVerdict(String verdict) {this.verdict = verdict;}
    }

    public static class Question {
        private String text = "is it so";

        public String getText() {return text;}

        public void setText(String text) {this.text = text;}
    }

    /** Client whose provider answers with the given raw content. */
    static final class CannedClient extends AbstractLLMClient<String> {
        private final String rawContent;

        CannedClient(String rawContent) {
            this.rawContent = rawContent;
        }

        @Override
        public APIDialect getDialect() {
            return APIDialect.BEDROCK_CONVERSE;
        }

        @Override
        protected TextWrapper<String> textWrapper() {
            return text -> text;
        }

        @Override
        protected <T> LLMResponse<T> doSingleResponse(LLMRequest<T> request, PreparedConversation prepared) {
            LLMResponse<T> response = new LLMResponse<>(request);
            response.setStopReason(LLMStopReason.END_TURN);
            response.getResponseMessage().overwriteRawContent(rawContent);
            return response;
        }

        @Override
        protected boolean is429Error(Exception e) {return false;}

        @Override
        protected boolean isServerError(Exception e) {return false;}

        @Override
        protected boolean isOverloadError(Exception e) {return false;}

        @Override
        protected RateLimitInfo extractRateLimitInfo(Exception e) {return null;}
    }

    /** The shape every one-call tool has: wire in getRequirements, converse in execute, unwrap around it. */
    @ToolName("strict_question")
    @ToolDescription(value = "Answers strictly.", readOnly = true)
    static final class StrictTool extends AbstractModelDependentTool<Question, StrictAnswer> {
        private final CannedClient client;

        StrictTool(CannedClient client) {
            super(Job.workflow("tool-correction-test", "tool-correction-test"), Grade.SMALL);
            this.client = client;
            client.setModel(TestModels.small());
            setInput(new Question());
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setReadOnly(true);
            ModelBinding binding = wireConversation(req, Depth.IMMEDIATE, OutputDeclaration.of(OutputSize.VERDICT),
                    StrictAnswer.class, "answer strictly");
            binding.resolve(TestModels.small());
            return req;
        }

        @Override
        protected LLMClient client(JobResources resources) {
            return client;
        }

        @Override
        public StrictAnswer execute(JobResources resources, JobContext<StrictAnswer> context) throws LLMReadableCheckedException {
            try {
                return converse(resources);
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    /**
     * A tool that builds its own conversation - one carrying an image, which the rendered
     * prompt cannot express - wires it through the supplier form and converses the same way,
     * so its malformed answer is corrected instead of failing the tool.
     */
    @ToolName("picture_question")
    @ToolDescription(value = "Answers strictly about a picture.", readOnly = true)
    static final class PictureTool extends AbstractModelDependentTool<Question, StrictAnswer> {
        private final CannedClient client;
        int built;

        PictureTool(CannedClient client) {
            super(Job.workflow("tool-correction-test", "tool-correction-test"), Grade.SMALL);
            this.client = client;
            client.setModel(TestModels.small());
            setInput(new Question());
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.setReadOnly(true);
            ModelBinding binding = wireConversation(req, Depth.IMMEDIATE, this::build);
            binding.resolve(TestModels.small());
            return req;
        }

        private ConversationContext build() {
            built++;
            ConversationContext conversation = new ConversationContext();
            conversation.setGrade(Grade.SMALL);
            conversation.setDepth(Depth.IMMEDIATE);
            conversation.setOutputDeclaration(OutputDeclaration.of(OutputSize.VERDICT));
            OutgoingMessage<StrictAnswer> message = new OutgoingMessage<>(new PojoResponseHandler<>(StrictAnswer.class));
            message.setRole("user");
            try {
                message.addImage(new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}), "image/png", "a picture");
            }
            catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            message.addText("answer strictly about the picture");
            conversation.getMessages().add(message);
            return conversation;
        }

        @Override
        protected LLMClient client(JobResources resources) {
            return client;
        }

        @Override
        public StrictAnswer execute(JobResources resources, JobContext<StrictAnswer> context) throws LLMReadableCheckedException {
            try {
                return converse(resources);
            }
            catch (Exception e) {
                throw LLMReadableCheckedException.unwrap(e);
            }
        }
    }

    @Test
    void aToolWithAConversationOfItsOwnIsCorrectedTheSameWay() throws Exception {
        PictureTool tool = new PictureTool(new CannedClient("{\"verdict\": {\"page\": \"yes\"}}"));
        tool.getRequirements();
        tool.getRequirements();
        assertEquals(1, tool.built, "the conversation is built once; every attempt wires a fresh binding onto it");
        assertThrows(ResponseCorrectionRetryException.class, () -> tool.execute(null, null),
                "an object where a string was asked for is a correction turn, never a system error");
        PictureTool answered = new PictureTool(new CannedClient("{\"verdict\": \"a cat\"}"));
        answered.getRequirements();
        assertEquals("a cat", answered.execute(null, null).getVerdict());
    }

    @Test
    void anUnparseableAnswerIsCorrectedThroughTheToolsOwnCatch_thenSurfaces() {
        StrictTool tool = new StrictTool(new CannedClient("{\"verdict\": \"yes\".trim()}"));
        tool.getRequirements();
        for (int attempt = 1; attempt <= ResponseCorrection.MAX_CORRECTIONS; attempt++) {
            assertThrows(ResponseCorrectionRetryException.class, () -> tool.execute(null, null),
                    "the retry signal passes through unwrap, so the dispatcher re-runs the tool on the corrected conversation");
        }
        assertThrows(JsonParseException.class, () -> tool.execute(null, null),
                "past the budget the parse failure surfaces as the correctable failure it is");
    }

    @Test
    void anAnswerWithoutItsRequiredFieldIsCorrected_thenSurfaces() {
        StrictTool tool = new StrictTool(new CannedClient("{}"));
        tool.getRequirements();
        for (int attempt = 1; attempt <= ResponseCorrection.MAX_CORRECTIONS; attempt++) {
            assertThrows(ResponseCorrectionRetryException.class, () -> tool.execute(null, null));
        }
        assertThrows(ResponseValidationException.class, () -> tool.execute(null, null),
                "required is required: past the budget the missing field fails the call, and no caller receives an answer with a hole in it");
    }

    @Test
    void aGoodAnswerIsTheAnswer() throws Exception {
        StrictTool tool = new StrictTool(new CannedClient("{\"verdict\": \"yes\"}"));
        tool.getRequirements();
        assertEquals("yes", tool.execute(null, null).getVerdict());
    }
}
