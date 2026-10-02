/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the EPO client does with a document a third party handed it, judged without a
 * transport: the reader is the boundary, and the response body is the hostile input.
 *
 * <p><b>A response cannot make this process fetch anything.</b> The reader has DTD support and
 * external-entity resolution switched off at construction, so an entity pointing at a local
 * file or at a URL is refused rather than expanded. What is refused is the resolution and not
 * the doctype token itself: a declaration that asks for nothing external is read normally, and
 * a reader that rejected it would drop responses EPO is entitled to send. This is the whole
 * reason the mapper is configured by hand instead of taken from the defaults, and until now
 * nothing proved the switches were still off. A library that changes its mind about a default
 * fails here rather than in production.
 *
 * <p><b>A well-formed response is still read.</b> The refusals mean nothing without the mirror,
 * so the ordinary EPO shapes - nested elements, attributes, repeated children - are pinned as
 * accepted and navigable.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-06)
 */
class EPOClientParsingTest {

    @Test
    void aResponseCarryingAnExternalEntityIsRefusedRatherThanExpanded() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE doc [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <doc><title>&secret;</title></doc>""";
        IOException refusal = assertThrows(IOException.class, () -> EPOClient.xmlToJson(xxe),
                "a body that asks this process to read a local file is refused at the reader, "
                        + "never expanded into the tree a tool then hands to a model");
        assertFalse(String.valueOf(refusal.getMessage()).contains("root:"),
                "and nothing from the targeted file appears in the failure either");
    }

    @Test
    void aResponseCarryingAnExternalEntityOverHttpIsRefusedTheSameWay() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE doc [<!ENTITY call SYSTEM "http://169.254.169.254/latest/meta-data/">]>
                <doc><title>&call;</title></doc>""";
        assertThrows(IOException.class, () -> EPOClient.xmlToJson(xxe),
                "an entity pointing at a network address would turn every EPO response into an "
                        + "outbound request of the responder's choosing");
    }

    @Test
    void aDoctypeCarryingNoEntityIsHarmlessAndTheDocumentIsStillRead() throws Exception {
        String withDoctype = """
                <?xml version="1.0"?>
                <!DOCTYPE doc><doc><title>ordinary</title></doc>""";
        JsonNode json = EPOClient.xmlToJson(withDoctype);
        assertEquals("ordinary", json.path("title").asText(),
                "the refusal is of entity resolution, not of the doctype token: a declaration that "
                        + "asks for nothing external costs the caller nothing, and refusing it would "
                        + "drop responses EPO is entitled to send");
    }

    @Test
    void anOrdinaryResponseIsReadAndNavigable() throws Exception {
        String xml = """
                <world-patent-data>
                  <exchange-document country="EP" doc-number="1000000" kind="A1">
                    <bibliographic-data>
                      <invention-title lang="en">A Title</invention-title>
                    </bibliographic-data>
                  </exchange-document>
                </world-patent-data>""";
        JsonNode json = EPOClient.xmlToJson(xml);
        assertEquals("EP", json.path("exchange-document").path("country").asText(),
                "an attribute is readable at the node that carried it");
        assertEquals("A Title", json.path("exchange-document").path("bibliographic-data")
                        .path("invention-title").path("").asText(),
                "element text survives the conversion the tool layer navigates");
    }

    @Test
    void repeatedElementsSurviveAsAnArray() throws Exception {
        String xml = """
                <family>
                  <member doc-number="1"/>
                  <member doc-number="2"/>
                </family>""";
        JsonNode members = EPOClient.xmlToJson(xml).path("member");
        assertTrue(members.isArray(), "a family with two members reads as two, not as the last one");
        assertEquals(2, members.size(), "both members are present");
    }

    @Test
    void inlineMarkupIsStrippedFromTextContent() {
        assertEquals("H2O is water", EPOClient.stripHtml("H<sub>2</sub>O is water"),
                "EPO full text carries inline markup that is noise to a model reading plain text");
        assertEquals("bold and italic", EPOClient.stripHtml("<b>bold</b> and <i>italic</i>"),
                "the tags go and the words stay, in order");
    }

    @Test
    void strippingLeavesNullAloneRatherThanInventingAnEmptyString() {
        assertNull(EPOClient.stripHtml(null),
                "an absent field stays absent - a caller that never had text does not receive one");
    }
}
