/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import ai.redouble.demo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import java.time.*;

/**
 * One text into one vector, on the deployment's frozen embeddings model. The tool declares
 * the embeddings channel and a token reservation; which model serves it is the catalog's
 * pin, never the tool's choice, because stored vectors compare only with vectors from the
 * model that produced them.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
@DisplayName(value = "Embed Text", action = "Embedding a text")
@ToolName("embed_text")
@ToolDescription(value = "Turns a text into a vector on the deployment's embeddings model.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 1, max = 1)
public class EmbedTextTool extends AbstractTool<TextToEmbed, Embedding> {
    /** The reservation's token estimate: a character count over this is a conservative token count for every tokenizer in use. */
    static final int CHARS_PER_TOKEN = 3;
    private ModelBinding binding;

    public EmbedTextTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofMinutes(2));
        setUpstreamRetries(DemoPolicy.UPSTREAM_RETRIES);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        binding = req.requireEmbeddings(input.getText().length() / CHARS_PER_TOKEN + 1);
        return req;
    }

    @Override
    public Embedding execute(JobResources resources, JobContext<Embedding> context) throws LLMReadableCheckedException {
        try {
            EmbeddingsClient client = resources.getEmbeddingsClient(binding.getModel());
            Embedding embedding = new Embedding();
            embedding.setKey(input.getKey());
            embedding.setModelId(binding.getModel().getId());
            embedding.setVector(client.calculateEmbedding(input.getText(), input.getPurpose()));
            return embedding;
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }
}
