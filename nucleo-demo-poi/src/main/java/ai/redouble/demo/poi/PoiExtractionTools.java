/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.poi;

import ai.redouble.demo.extract.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.tools.*;

/**
 * The Apache POI / PDFBox reader, chosen over the engine's toolkit-free default whenever this
 * module is on the classpath (a higher priority). It reads PDFs locally, page by page, so a PDF
 * with a text layer never costs a model call. A native build that leaves this module off falls
 * back to the toolkit-free reader, which is why the module exists separately.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public class PoiExtractionTools implements ExtractionTools {
    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean readsPdfLocally() {
        return true;
    }

    @Override
    public AbstractTool<FileRef, Extraction> deterministic(Identifiable parent, FileRef input) {
        PoiDeterministicExtractTool tool = new PoiDeterministicExtractTool(parent);
        tool.setInput(input);
        return tool;
    }

    @Override
    public AbstractTool<FileRef, Extraction> vision(Identifiable parent, FileRef input) {
        PdfVisionExtractTool tool = new PdfVisionExtractTool(parent);
        tool.setInput(input);
        return tool;
    }
}
