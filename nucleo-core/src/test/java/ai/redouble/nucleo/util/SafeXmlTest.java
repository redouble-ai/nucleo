/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.util;

import org.junit.jupiter.api.*;
import org.w3c.dom.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SafeXml#parse}: an XML string becomes a DOM document; a DTD is allowed, internal
 * entities included, because PubMed and NCBI responses carry one; an external entity is never
 * resolved, so a reference to one contributes nothing; and the parser is safe to call from any
 * thread.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
class SafeXmlTest {

    @Test
    void anXmlStringBecomesADomDocument() throws Exception {
        Document document = SafeXml.parse("<root><item>1</item></root>");
        assertEquals("root", document.getDocumentElement().getTagName());
        assertEquals("1", document.getElementsByTagName("item").item(0).getTextContent());
    }

    @Test
    void aDtdWithInternalEntitiesIsAllowed() throws Exception {
        Document document = SafeXml.parse("<!DOCTYPE r [<!ENTITY greeting \"hello\">]><r>&greeting;</r>");
        assertEquals("hello", document.getDocumentElement().getTextContent(), "an internal entity declared by the DTD expands");
    }

    @Test
    void aPubMedStyleExternalDtdReferenceIsAllowedAndNeverFetched() throws Exception {
        // The named DTD does not exist: a parser that tried to read it would fail here
        Document document = SafeXml.parse("<!DOCTYPE PubmedArticleSet PUBLIC \"-//NLM//DTD PubMedArticle, 1st January 2024//EN\""
                + " \"file:///nucleo-safe-xml-never-fetched.dtd\"><PubmedArticleSet><PMID>1</PMID></PubmedArticleSet>");
        assertEquals("1", document.getElementsByTagName("PMID").item(0).getTextContent(),
                "a response that names its DTD parses without the DTD being read");
    }

    @Test
    void anExternalEntityIsNeverResolved() throws Exception {
        Document document = SafeXml.parse("<!DOCTYPE r [<!ENTITY outside SYSTEM \"file:///nucleo-safe-xml-probe\">]><r>&outside;</r>");
        assertEquals("", document.getDocumentElement().getTextContent(), "the reference to an external entity contributes nothing");
    }

    @Test
    void parsingIsSafeFromAnyThread() throws Exception {
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> roots = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String name = "root" + i;
                roots.add(threads.submit(() -> SafeXml.parse("<" + name + "/>").getDocumentElement().getTagName()));
            }
            for (int i = 0; i < roots.size(); i++) {
                assertEquals("root" + i, roots.get(i).get(), "each call has its own parser");
            }
        }
    }
}
