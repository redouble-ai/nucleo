/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import java.util.*;

/**
 * What came out of one file: its text, or the reason there is none, or both. A PDF whose
 * pages are pictures lists them in {@code scanPages} for the vision tier, and a mixed PDF
 * lists only the pages that carry no text, keeping the text of the rest; {@code textLayer}
 * is false when a file the code expected to read as text turned out not to be text.
 * {@code note} says what the reader saw when it could not read.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class Extraction {
    private String path;
    private String text;
    private boolean textLayer = true;
    private Integer pages;
    private List<Integer> scanPages;
    private String note;

    public String getPath() {return path;}

    public void setPath(String path) {this.path = path;}

    public String getText() {return text;}

    public void setText(String text) {this.text = text;}

    public boolean isTextLayer() {return textLayer;}

    public void setTextLayer(boolean textLayer) {this.textLayer = textLayer;}

    public Integer getPages() {return pages;}

    public void setPages(Integer pages) {this.pages = pages;}

    /** One-based numbers of the pages that are pictures, or null when every page carries text. */
    public List<Integer> getScanPages() {return scanPages;}

    public void setScanPages(List<Integer> scanPages) {this.scanPages = scanPages;}

    public String getNote() {return note;}

    public void setNote(String note) {this.note = note;}
}
