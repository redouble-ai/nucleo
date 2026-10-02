/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.util;

import org.w3c.dom.*;
import org.xml.sax.*;

import javax.xml.*;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.charset.*;

/**
 * XML parsing safe against XXE. A DTD is allowed, internal entity declarations included, because
 * PubMed and NCBI responses carry one; the external subset a document names is never fetched and
 * an external entity is never resolved, so neither can exfiltrate a file or reach a host.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-12)
 */
public class SafeXml {

    private static final DocumentBuilderFactory FACTORY;
    static {
        FACTORY = DocumentBuilderFactory.newInstance();
        try {
            // Disable external entity resolution (the dangerous part of XXE) and the fetch of the
            // external DTD subset, but accept the DOCTYPE itself: every PubMed and PMC response
            // names an NCBI DTD, and a parser that had to read it would fail or phone home.
            FACTORY.setFeature("http://xml.org/sax/features/external-general-entities", false);
            FACTORY.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            FACTORY.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        }
        catch (ParserConfigurationException e) {
            throw new RuntimeException("Failed to configure safe XML parser", e);
        }
        try {
            // Additional hardening - not supported by all parsers (e.g. Apache Xerces)
            FACTORY.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            FACTORY.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        }
        catch (IllegalArgumentException ignored) {
            // Xerces doesn't support these attributes; the feature flags above already
            // disable external entity resolution, so this is safe to skip
        }
    }

    /**
     * The XML string as a DOM Document. A new DocumentBuilder per call, because a builder is not
     * thread-safe and this method may be called from any thread.
     */
    public static Document parse(String xml) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilder builder = FACTORY.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
