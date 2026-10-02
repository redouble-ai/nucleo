/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.prompt.skill.*;
import ai.redouble.nucleo.tools.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.util.*;

/**
 * Dynamic {@link ToolProvider} for {@link RequestSkillTool}, the skill counterpart of
 * {@link RequestToolsProvider}. The input schema is built from the thinker's reconciled skill
 * catalog: the {@code skillNames} array enumerates the catalog as {@code oneOf} const entries,
 * each carrying the skill's own description, so the model chooses from what it may have and the
 * LLM provider refuses any other name before it reaches admission. The catalog never enters the
 * prompt as free text.
 *
 * <p>Registered per turn by the thinker's {@code reconcileToolRegistry}, with the catalog as it
 * stands that turn, and unregistered when the catalog is empty. Read-only by declaration: admitting
 * a skill changes the conversation's own preamble and nothing outside it, so a read-only thinker
 * keeps it on the palette where {@code request_tools} is withheld.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public final class RequestSkillProvider implements ToolProvider {
    /** Stable name used for registry lookups and unregistration. */
    public static final String NAME = "request_skill";

    /** The input's one field as the wire spells it: {@link RequestSkillInput#getSkillNames()} in snake_case. */
    public static final String SKILL_NAMES = "skill_names";
    private static final String DESCRIPTION =
            "Admit skills from the catalog. Pass skill names; each admitted skill's instructions ride every subsequent call in this conversation.";
    private final Set<Skill> catalog;
    private final String schemaJson;

    public RequestSkillProvider(Set<Skill> catalog) {
        this.catalog = catalog;
        this.schemaJson = buildSchema(catalog);
    }

    private static String buildSchema(Set<Skill> catalog) {
        ObjectNode root = NucleoJsonSerializer.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        // snake_case, the spelling every class-backed schema publishes and the one the
        // serializer reads back; a camelCase key here is silently dropped at parse time
        ObjectNode skillNames = properties.putObject(SKILL_NAMES);
        skillNames.put("type", "array");
        skillNames.put("description", "Names of skills to admit from the catalog");
        ObjectNode items = skillNames.putObject("items");
        ArrayNode oneOf = items.putArray("oneOf");
        for (Skill skill : catalog) {
            ObjectNode entry = oneOf.addObject();
            entry.put("const", skill.name());
            entry.put("description", descriptionOf(skill));
        }
        ArrayNode required = root.putArray("required");
        required.add(SKILL_NAMES);
        return root.toString();
    }

    /** The skill's own description text, the "what and when to use" line the model chooses by. */
    static String descriptionOf(Skill skill) {
        return skill.description() != null && skill.description().content() != null
                ? skill.description().content().asText()
                : "";
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public String schemaJson() {
        return schemaJson;
    }

    @Override
    public ToolWeight weight() {
        return DefaultToolWeights.API_CALL_DEFAULT;
    }

    @Override
    public String displayName() {
        return "Request Skill";
    }

    @Override
    public String actionVerb() {
        return "Requesting Skills";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Class<?> inputType() {
        return RequestSkillInput.class;
    }

    @Override
    public Class<? extends Tool> toolClass() {
        return RequestSkillTool.class;
    }

    @Override
    public Object parseInput(JsonNode raw) throws CorrectableLLMException {
        if (raw == null) {
            throw new InvalidInputException(NAME, "null", "input is null");
        }
        try {
            return NucleoJsonSerializer.convert(raw, RequestSkillInput.class);
        }
        catch (Exception e) {
            // The refusal is composed from the contract, never from the payload: the
            // decoder's complaint quotes what the model sent and stays on the cause
            throw new InvalidInputException(SKILL_NAMES, RequestSkillInput.class.getSimpleName(),
                    "an array of skill names from the catalog", e);
        }
    }

    @Override
    public Tool<?, ?> create(Identifiable parent) throws LLMReadableCheckedException {
        return new RequestSkillTool(parent);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RequestSkillProvider;
    }

    @Override
    public int hashCode() {
        return RequestSkillProvider.class.hashCode();
    }

    @Override
    public String toString() {
        return "RequestSkillProvider[catalog=" + catalog.size() + "]";
    }
}
