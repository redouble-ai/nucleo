/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import java.util.*;

/**
 * The one input every extraction tool takes: which file. {@code asText} tells the
 * deterministic reader to keep a text whatever share of it failed to decode, which is what
 * the classifier decided for a file the code had doubted; {@code pages} tells the vision
 * tier which pages of a PDF to transcribe, the ones the code found no text on, or all of
 * them when null.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class FileRef {
    private String path;
    private boolean asText;
    private List<Integer> pages;

    public String getPath() {return path;}

    public void setPath(String path) {this.path = path;}

    public boolean isAsText() {return asText;}

    public void setAsText(boolean asText) {this.asText = asText;}

    public List<Integer> getPages() {return pages;}

    public void setPages(List<Integer> pages) {this.pages = pages;}
}
