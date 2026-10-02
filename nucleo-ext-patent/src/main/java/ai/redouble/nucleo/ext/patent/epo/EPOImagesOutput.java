/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.epo;

import ai.redouble.nucleo.harness.schema.*;

/**
 * Output from EPO patent images metadata retrieval.
 * Contains URLs for downloading the patent PDF and representative drawing.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class EPOImagesOutput {
    @LLMDescription("Patent number")
    private String patentNumber;
    @LLMDescription("Total number of pages in the patent document")
    private Integer pageCount;
    @LLMDescription("Direct download URL for the full PDF. Pass to a document-parsing tool for content extraction.")
    private String pdfUrl;
    @LLMDescription("URL for the first drawing page as a representative image")
    private String representativeDrawingUrl;
    public EPOImagesOutput() {
    }
    public String getPatentNumber() {
        return patentNumber;
    }
    public void setPatentNumber(String patentNumber) {
        this.patentNumber = patentNumber;
    }
    public Integer getPageCount() {
        return pageCount;
    }
    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }
    public String getPdfUrl() {
        return pdfUrl;
    }
    public void setPdfUrl(String pdfUrl) {
        this.pdfUrl = pdfUrl;
    }
    public String getRepresentativeDrawingUrl() {
        return representativeDrawingUrl;
    }
    public void setRepresentativeDrawingUrl(String representativeDrawingUrl) {
        this.representativeDrawingUrl = representativeDrawingUrl;
    }
}
