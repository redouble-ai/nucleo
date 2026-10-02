/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.registry;

import ai.redouble.nucleo.harness.schema.*;
import ai.redouble.nucleo.prompt.skill.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@code request_skill}'s schema constraint, the same one {@code request_tools} rests on:
 * the skill-name field enumerates the reconciled catalog as {@code oneOf} const entries, each
 * carrying the skill's own description, so the provider's native validation refuses a name that
 * is not on the catalog before it can reach admission.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public class RequestSkillSchemaTest {

    @Test
    void schemaEnumeratesExactlyTheCatalog_withEachSkillsOwnDescription() throws IOException {
        Set<Skill> catalog = new LinkedHashSet<>(List.of(
                SkillFixtures.skill("citations", "How to cite sources.", "test.bundle"),
                SkillFixtures.skill("brevity", "Answer in one line.", "test.bundle")));
        RequestSkillProvider provider = new RequestSkillProvider(catalog);

        JsonNode schema = NucleoJsonSerializer.readTree(provider.schemaJson());
        JsonNode names = schema.path("properties").path(RequestSkillProvider.SKILL_NAMES);
        assertEquals("array", names.path("type").asText());
        JsonNode oneOf = names.path("items").path("oneOf");
        assertTrue(oneOf.isArray(), "the names field is an enumerated oneOf, not a free string: " + schema);

        Map<String, String> offered = new LinkedHashMap<>();
        for (JsonNode entry : oneOf) {
            offered.put(entry.path("const").asText(), entry.path("description").asText());
        }
        assertEquals(Set.of("citations", "brevity"), offered.keySet(),
                "exactly the catalog's names - nothing more to hallucinate against, nothing missing");
        assertEquals("How to cite sources.", offered.get("citations"),
                "the entry's description is the skill's own, the text the model chooses by");
        assertEquals("Answer in one line.", offered.get("brevity"));
        assertEquals(RequestSkillProvider.SKILL_NAMES, schema.path("required").get(0).asText(), "the names are required");
    }

    @Test
    void theProviderIsReadOnly_andOneInstancePerRegistry() {
        RequestSkillProvider a = new RequestSkillProvider(Set.of(SkillFixtures.skill("a", "A.", "b")));
        RequestSkillProvider b = new RequestSkillProvider(Set.of(SkillFixtures.skill("b", "B.", "b")));
        assertTrue(a.readOnly(), "admitting a skill changes the conversation's own preamble and nothing else");
        assertEquals(a, b, "two instances are the same registry entry, so re-registering per turn replaces rather than duplicates");
        assertEquals(RequestSkillProvider.NAME, a.name());
        assertEquals(RequestSkillTool.class, a.toolClass());
    }

    @Test
    void parseInputRefusesNullAndUnparseable_asCorrectableErrors_neverEchoingThePayload() throws IOException {
        RequestSkillProvider provider = new RequestSkillProvider(Set.of(SkillFixtures.skill("a", "A.", "b")));
        assertThrows(ai.redouble.nucleo.harness.errors.InvalidInputException.class, () -> provider.parseInput(null));
        // the value in an impossible position carries a canary: the refusal is composed from
        // the contract, and the decoder's complaint - which quotes the payload - stays on the cause
        String canary = "zqx7skillcanary";
        ai.redouble.nucleo.harness.errors.InvalidInputException refusal =
                assertThrows(ai.redouble.nucleo.harness.errors.InvalidInputException.class,
                        () -> provider.parseInput(NucleoJsonSerializer.readTree(
                                "{\"" + RequestSkillProvider.SKILL_NAMES + "\": {\"" + canary + "\": 1}}")));
        assertFalse(refusal.getMessage().contains(canary),
                "the refusal never quotes what the model sent: " + refusal.getMessage());
        assertFalse(refusal.getLLMMessage().contains(canary),
                "the model-facing text is composed the same way: " + refusal.getLLMMessage());
        assertNotNull(refusal.getCause(),
                "the decoder's complaint survives on the cause, where only a stack trace carries it");
    }

    /**
     * A call spelled as the schema publishes it parses to the names. The serializer maps
     * snake_case and drops unknown keys silently, so the published spelling and the parsed
     * one have to be the same string, or every call arrives empty.
     */
    @Test
    void aCallSpelledAsTheSchemaPublishesItParsesToTheNames() throws Exception {
        RequestSkillProvider provider = new RequestSkillProvider(Set.of(SkillFixtures.skill("a", "A.", "b")));
        JsonNode schema = NucleoJsonSerializer.readTree(provider.schemaJson());
        String published = schema.path("required").get(0).asText();
        assertTrue(schema.path("properties").has(published));
        Object parsed = provider.parseInput(NucleoJsonSerializer.readTree("{\"" + published + "\": [\"a\"]}"));
        assertInstanceOf(RequestSkillInput.class, parsed);
        assertEquals(List.of("a"), ((RequestSkillInput) parsed).getSkillNames());
    }
}
