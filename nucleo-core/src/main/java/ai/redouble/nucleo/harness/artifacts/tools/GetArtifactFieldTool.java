/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts.tools;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.tools.*;

import java.time.*;

/**
 * Tool for retrieving full content of a specific artifact field.
 *
 * <p>Use when you need complete text that was summarized in context. This is how a model
 * reads the whole of a field it was shown a summary of. The value is served as the
 * serializer writes it: a text as it is, any other value as its JSON, an artifact inside it
 * as its reference. The name is taken in snake_case or camelCase, and a value inside a
 * field is addressed by the field's name followed by a JSON pointer
 * ({@code data/results/0/title}). A field marked {@code @LLMContextIgnore} is no field
 * here.
 *
 * <p>Failures are typed refusals, never flags in the output: a reference the registry does
 * not resolve is {@link ResourceNotFoundException} (closed - a hallucinated ref never
 * reaches another artifact's content), a name that is no field of the artifact is
 * {@link InvalidInputException} listing the addressable fields, and a missing registry is
 * the caller's wiring fault, {@link SystemException}. A field that exists and holds null
 * returns an output with no content, because null is what the data is.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-01-10)
 */
@DisplayName(value = "Get Artifact Field", action = "Retrieving artifact field content")
@ToolName("get_artifact_field")
@LLMDescription("Get the full content of a specific field from an artifact. Use when you need the complete value of a field that is shown as a [SUMMARY: N chars] summary. A value that is not text is returned as JSON.")
@ReadOnly
@ToolWeight(type = ToolType.IN_MEMORY)
public class GetArtifactFieldTool extends AbstractTool<GetArtifactFieldInput, GetArtifactFieldOutput> implements ArtifactRegistryAware {
    private ArtifactRegistry registry;

    public GetArtifactFieldTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofSeconds(5));
    }

    @Override
    public void setArtifactRegistry(ArtifactRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ArtifactRegistry getArtifactRegistry() {
        return registry;
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        return req;
    }

    @Override
    public GetArtifactFieldOutput execute(JobResources resources, JobContext<GetArtifactFieldOutput> context) throws LLMReadableCheckedException {
        context.publish("Looking up artifact", 10);
        if (registry == null) {
            throw new SystemException("GetArtifactFieldTool",
                    "get_artifact_field runs only as a tool of a thinker, which injects the conversation's artifact registry before submission", null);
        }
        // A reference that does not resolve is a closed failure: a hallucinated or
        // misremembered ref gets a correctable not-found, never another artifact's content
        Artifact artifact = registry.get(input.getArtifactRef());
        if (artifact == null) {
            throw new ResourceNotFoundException("artifact", input.getArtifactRef());
        }
        context.publish("Extracting field", 50);
        // A name that resolves to nothing is the model's to fix, so the refusal lists the
        // fields that can be read
        String content = ModelFacingFields.read(artifact, input.getFieldName());
        GetArtifactFieldOutput output = new GetArtifactFieldOutput();
        output.setFieldName(input.getFieldName());
        if (content == null) {
            // The field exists and holds null - that IS the content, never a refusal
            context.publish("Complete", 100);
            return output;
        }
        output.setLength(content.length());
        // Apply offset/maxChars chunking
        int maxChars = input.getMaxChars() != null ? input.getMaxChars() : 10000;
        int offset = input.getOffset() != null ? input.getOffset() : 0;
        offset = Math.min(offset, content.length());
        int end = Math.min(offset + maxChars, content.length());
        output.setContent(content.substring(offset, end));
        output.setTruncated(end < content.length());
        context.publish("Complete", 100);
        return output;
    }
}
