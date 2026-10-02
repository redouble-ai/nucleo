/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import com.fasterxml.jackson.databind.*;

/**
 * Reads the text sections of an OPS full-text document: claims and description arrive as a
 * fulltext-document (or an exchange-document) holding one section per language, each a list
 * of paragraphs. English is preferred, the first language is the fallback, and when the JSON
 * view holds nothing the raw XML element is flattened instead.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
final class EPOText {
    private EPOText() {
    }

    /** The document node of an OPS full-text response, whichever envelope it came in. */
    static JsonNode fulltextDocument(JsonNode root) {
        JsonNode ftd = root.path("world-patent-data").path("ftxt").path("fulltext-documents").path("fulltext-document");
        if (ftd.isMissingNode()) {
            ftd = root.path("world-patent-data").path("exchange-documents").path("exchange-document");
        }
        if (ftd.isArray() && ftd.size() > 0) {
            ftd = ftd.get(0);
        }
        return ftd;
    }

    /**
     * The paragraphs of one section ({@code claims}, {@code description}) joined by the
     * separator: the English one when it has text, else the first language's, else null.
     */
    static String sectionText(JsonNode root, String section, String paragraphElement, String separator) {
        JsonNode node = fulltextDocument(root).path(section);
        if (node.isArray()) {
            for (JsonNode s : node) {
                String lang = s.path("lang").asText(null);
                if ("en".equals(lang) || lang == null) {
                    String text = paragraphs(s, paragraphElement, separator);
                    if (text != null) {
                        return text;
                    }
                }
            }
            if (node.size() > 0) {
                return paragraphs(node.get(0), paragraphElement, separator);
            }
        }
        else if (!node.isMissingNode()) {
            return paragraphs(node, paragraphElement, separator);
        }
        return null;
    }

    private static String paragraphs(JsonNode parent, String elementName, String separator) {
        JsonNode p = parent.path(elementName);
        StringBuilder sb = new StringBuilder();
        if (p.isArray()) {
            for (JsonNode para : p) {
                if (!sb.isEmpty()) {
                    sb.append(separator);
                }
                sb.append(para.asText(""));
            }
        }
        else if (!p.isMissingNode()) {
            sb.append(p.asText(""));
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** One element of the raw XML as plain text, tags stripped and whitespace collapsed; null when the element is absent. */
    static String rawText(String xml, String tagName) {
        int startIdx = xml.indexOf("<" + tagName);
        if (startIdx < 0) {
            return null;
        }
        int endIdx = xml.lastIndexOf("</" + tagName + ">");
        if (endIdx < 0) {
            return null;
        }
        String content = xml.substring(startIdx, endIdx + tagName.length() + 3);
        return content.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
