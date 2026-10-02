/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.tools.*;

/**
 * The engine's default reader, which pulls no font or graphics toolkit and so compiles to a
 * native image on any OS: Office documents are read as the zip-of-XML they are
 * ({@link DeterministicExtractTool}) and PDFs go whole to the model tier
 * ({@link VisionExtractTool}). A host that wants Apache POI and PDFBox instead ships a
 * higher-priority {@link ExtractionTools} and this one steps aside.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public class AwtFreeExtractionTools implements ExtractionTools {
    @Override
    public boolean readsPdfLocally() {
        return false;
    }

    @Override
    public AbstractTool<FileRef, Extraction> deterministic(Identifiable parent, FileRef input) {
        DeterministicExtractTool tool = new DeterministicExtractTool(parent);
        tool.setInput(input);
        return tool;
    }

    @Override
    public AbstractTool<FileRef, Extraction> vision(Identifiable parent, FileRef input) {
        VisionExtractTool tool = new VisionExtractTool(parent);
        tool.setInput(input);
        return tool;
    }
}
