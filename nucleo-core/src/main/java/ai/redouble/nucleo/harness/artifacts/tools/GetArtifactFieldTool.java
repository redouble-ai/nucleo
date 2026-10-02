/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts.tools;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;

import java.time.*;

/**
 * Tool for retrieving full content of a specific artifact field.
 *
 * <p>Use when you need complete text that was summarized in context.
 * The tool reads the field directly from the live artifact via reflection,
 * accepting either snake_case or camelCase field names.
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
@ToolDescription(value = "Get the full content of a specific field from an artifact. Use when you need the complete text that was summarized in context.", readOnly = true)
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
        // The name resolves snake_case or camelCase against the class hierarchy; a name that
        // resolves to nothing is the model's to fix, so the refusal lists the addressable fields
        java.lang.reflect.Field field = ReflectiveFields.find(artifact.getClass(), input.getFieldName());
        if (field == null) {
            throw new InvalidInputException("fieldName", input.getFieldName(),
                    "a field of " + artifact.getClass().getSimpleName() + ": " + ReflectiveFields.fieldNames(artifact.getClass()));
        }
        String content = readField(artifact, field);
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

    /** The field's value as text, null when the artifact holds none. */
    private String readField(Artifact artifact, java.lang.reflect.Field field) {
        try {
            Object value = field.get(artifact);
            return value != null ? value.toString() : null;
        }
        catch (IllegalAccessException e) {
            throw new IllegalStateException("Field " + field.getName() + " on "
                    + artifact.getClass().getSimpleName() + " resolved but could not be read", e);
        }
    }
}
