/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.tools.*;

import java.util.*;

/**
 * How a deployment reads the formats code can open without a model, chosen by which
 * implementation is on the classpath. The engine ships {@link AwtFreeExtractionTools}, which
 * reads Office documents as the zip-of-XML they are and sends PDFs whole to the model, so it
 * needs no font or graphics toolkit and compiles to a native image on any OS. A host that
 * prefers Apache POI and PDFBox - richer local Office and PDF reading, at the cost of the AWT
 * those libraries pull - puts a higher-priority implementation on the classpath; the one with
 * the highest {@link #priority()} wins, so adding the POI module changes the reader without a
 * code edit, and leaving it off the native build falls back to the toolkit-free reader.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public interface ExtractionTools {
    /** The highest-priority implementation on the classpath: the POI reader when present, else the engine's toolkit-free one. */
    static ExtractionTools resolve() {
        return ServiceLoader.load(ExtractionTools.class).stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(ExtractionTools::priority))
                .orElseThrow(() -> new IllegalStateException("No ExtractionTools implementation on the classpath"));
    }

    /** Ties break toward the higher number; the engine's toolkit-free default is 0. */
    default int priority() {
        return 0;
    }

    /** True when this reader opens PDFs itself (POI/PDFBox); false when a PDF goes whole to the model tier. */
    boolean readsPdfLocally();

    /** The no-model reader for text and Office documents (and, when {@link #readsPdfLocally()}, PDFs). */
    AbstractTool<FileRef, Extraction> deterministic(Identifiable parent, FileRef input);

    /** The model reader for images (and PDFs when {@link #readsPdfLocally()} is false, or a PDF's scanned pages when true). */
    AbstractTool<FileRef, Extraction> vision(Identifiable parent, FileRef input);
}
