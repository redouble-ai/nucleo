/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.docs;

import java.nio.file.*;
import java.util.*;

/**
 * Generates the sidebar navigation shared by every page of the documentation site, from
 * the contents: the front matter at the top, then one numbered, collapsible section per
 * part, its pages in reading order and a nested item under the item it nests under in
 * {@code CONTENTS.md}. Beside the name at its top sits the version the site documents, as
 * a badge. A site that is published beside other versions has the badge name the list of
 * them, and page JavaScript turns it into a choice among those versions; a site that
 * stands alone, like the copy inside the demo, names no list and keeps the plain badge.
 * The nav carries no per-page "active" marker; page JavaScript
 * highlights the link matching location.pathname at load time and expands its part.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
public class NavGenerator {
    /** The address, relative to a page, of the list of versions a published site sits beside. */
    public static final String VERSIONS_LIST = "../versions.json";

    public static String generate(Contents contents, Map<Path, GenerateDocs.Page> pages, String version, boolean versionsBeside) {
        StringBuilder out = new StringBuilder();
        out.append("<nav>\n");
        out.append("    <div class=\"nav-header\">\n");
        out.append("        <h3>Nucleo</h3>\n");
        out.append("        <span class=\"nav-version\" id=\"nav-version\"").append(versionsBeside ? " data-versions=\"" + VERSIONS_LIST + "\"" : "").append(">")
                .append(GenerateDocs.escape(version)).append("</span>\n");
        out.append("    </div>\n");
        out.append("    <ul class=\"nav-top\">\n");
        for (Contents.Entry entry : contents.frontMatter()) {
            out.append(navLink(pages.get(entry.source()), "        "));
        }
        out.append("    </ul>\n");
        int number = 0;
        for (Contents.Part part : contents.parts()) {
            number++;
            String sectionId = "part-" + number;
            out.append("    <div class=\"nav-section collapsed\" data-section=\"").append(sectionId).append("\">\n");
            out.append("        <h4 class=\"nav-section-header\" onclick=\"toggleSection('").append(sectionId).append("')\">\n");
            out.append("            <span class=\"section-icon\">").append(number).append("</span>\n");
            out.append("            <span class=\"section-title\">").append(GenerateDocs.escape(part.title())).append("</span>\n");
            out.append("            <span class=\"collapse-icon\">+</span>\n");
            out.append("        </h4>\n");
            out.append("        <ul class=\"nav-section-content\">\n");
            List<Contents.Entry> entries = part.entries();
            for (int i = 0; i < entries.size(); i++) {
                Contents.Entry entry = entries.get(i);
                if (entry.depth() == 1) {
                    continue;
                }
                out.append(navLink(pages.get(entry.source()), "            "));
                int child = i + 1;
                if (child < entries.size() && entries.get(child).depth() == 1) {
                    out.append("            <ul class=\"nav-nested\">\n");
                    while (child < entries.size() && entries.get(child).depth() == 1) {
                        out.append(navLink(pages.get(entries.get(child).source()), "                "));
                        child++;
                    }
                    out.append("            </ul>\n");
                }
            }
            out.append("        </ul>\n");
            out.append("    </div>\n");
        }
        out.append("</nav>\n");
        return out.toString();
    }

    private static String navLink(GenerateDocs.Page page, String indent) {
        return indent + "<li><a href=\"" + page.id() + ".html\">" + GenerateDocs.escape(page.title()) + "</a></li>\n";
    }
}
