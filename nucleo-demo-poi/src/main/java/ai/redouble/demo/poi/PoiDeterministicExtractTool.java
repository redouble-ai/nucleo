/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.poi;

import ai.redouble.demo.extract.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.text.*;
import org.apache.poi.extractor.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * The POI/PDFBox reader: text the code can read with the mature Office and PDF libraries. A
 * PDF goes through PDFBox page by page, and each page that carries no text is listed for the
 * vision tier while the text of the rest is kept; an Office document goes through POI; anything
 * unsigned is read as UTF-8 text, {@code textLayer} false when too much of it failed to decode.
 * This reader pulls AWT through those libraries, so it is a JVM-only alternative to the engine's
 * toolkit-free reader and is left off a native image.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
@DisplayName(value = "Extract Text", action = "Reading a file")
@ToolName("extract_text")
@ToolDescription(value = "Reads the text of a file by its format with Apache POI and PDFBox, without a model.", readOnly = true)
@ToolWeight(type = ToolType.IN_MEMORY)
public class PoiDeterministicExtractTool extends AbstractTool<FileRef, Extraction> {
    /** A page with fewer characters than this of text is a picture or a blank, not a text layer. */
    static final int TEXT_LAYER_CHARS_PER_PAGE = 20;
    /** Above this share of undecodable characters, a file is not text whatever its extension says. */
    static final double UNDECODABLE_SHARE = 0.05;

    public PoiDeterministicExtractTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofMinutes(2));
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        return req;
    }

    @Override
    public Extraction execute(JobResources resources, JobContext<Extraction> context) throws LLMReadableCheckedException {
        Path file = Path.of(input.getPath());
        Extraction extraction = new Extraction();
        extraction.setPath(input.getPath());
        try {
            Sniff.Kind kind = Sniff.of(file);
            switch (kind) {
                case PDF -> readPdf(file, extraction);
                case OFFICE -> {
                    try (POITextExtractor extractor = ExtractorFactory.createExtractor(file.toFile())) {
                        extraction.setText(extractor.getText());
                    }
                }
                case UNSIGNED -> readText(file, extraction);
                default -> throw new InvalidInputException("path", input.getPath(),
                        "a format the deterministic tier reads: text, PDF or Office, and this is " + kind);
            }
        }
        catch (IOException e) {
            throw new SystemException("extract_text", "Cannot read " + input.getPath(), e);
        }
        return extraction;
    }

    private void readText(Path file, Extraction extraction) throws IOException {
        // malformed bytes become the replacement character: the file's text, with its damage counted
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        StringBuilder text = new StringBuilder();
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), decoder)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
            }
        }
        int undecodable = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '�' || (c < 0x20 && c != '\n' && c != '\r' && c != '\t')) {
                undecodable++;
            }
        }
        if (text.length() > 0 && (double) undecodable / text.length() > UNDECODABLE_SHARE) {
            if (input.isAsText()) {
                extraction.setNote(undecodable + " of " + text.length() + " characters did not decode; kept as the classifier decided");
            }
            else {
                extraction.setTextLayer(false);
                extraction.setNote("not text: " + undecodable + " of " + text.length() + " characters do not decode");
                return;
            }
        }
        extraction.setText(text.toString());
    }

    private static void readPdf(Path file, Extraction extraction) throws IOException {
        try (PDDocument document = PDDocument.load(file.toFile())) {
            int pages = document.getNumberOfPages();
            extraction.setPages(pages);
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder text = new StringBuilder();
            List<Integer> scans = new ArrayList<>();
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                if (pageText.strip().length() < TEXT_LAYER_CHARS_PER_PAGE) {
                    scans.add(page);
                }
                else {
                    text.append(pageText);
                }
            }
            if (!scans.isEmpty()) {
                extraction.setScanPages(scans);
                extraction.setNote(scans.size() == pages
                        ? "no text layer: " + pages + " page(s) of pictures"
                        : scans.size() + " of " + pages + " pages carry no text and go to the vision tier");
            }
            if (text.length() > 0) {
                extraction.setText(text.toString());
            }
            else {
                extraction.setTextLayer(false);
            }
        }
    }
}
