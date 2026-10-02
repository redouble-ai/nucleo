/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.poi;

import ai.redouble.demo.*;
import ai.redouble.demo.extract.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.rendering.*;
import javax.imageio.*;
import java.awt.image.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * The vision reader for the POI/PDFBox host: an image, or a PDF whose pages are pictures. Image
 * files are sent as images; a PDF is rendered page by page with PDFBox (which pulls AWT) and the
 * pages are sent as images, so this reader is a JVM-only alternative left off a native image, where
 * the engine's toolkit-free reader sends the PDF whole instead.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
@DisplayName(value = "Transcribe Images", action = "Reading a scan with a model")
@ToolName("transcribe_images")
@ToolDescription(value = "Transcribes the text on an image or a scanned PDF with a vision-capable model.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 5, max = 5)
public class PdfVisionExtractTool extends AbstractModelDependentTool<FileRef, Extraction> {
    /** Pages of a scanned PDF sent per file; a longer scan is transcribed up to here and says so. */
    static final int MAX_PAGES = 8;
    static final float RENDER_DPI = 110f;
    private static final String INSTRUCTIONS = "Transcribe every word of text visible in the image(s), in reading order,"
            + " page after page, into the one string field text, pages separated by a blank line. Keep tables as rows."
            + " Do not summarize, translate or comment. If there is no readable text, say so in no_text and leave text empty.";
    private int pagesSent;
    private int pagesAsked;
    private Integer pagesTotal;

    public PdfVisionExtractTool(Identifiable parent) {
        super(parent, Grade.SMALL);
        setTimeout(Duration.ofMinutes(6));
        setUpstreamRetries(DemoPolicy.UPSTREAM_RETRIES);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        // every file goes as images, a PDF's pages rendered: the request declares it, so the picker
        // serves an entry that sees; wired through the base so a malformed transcription is corrected
        ModelBinding binding = wireConversation(req, Depth.IMMEDIATE, this::build);
        binding.setSends(Set.of(Input.IMAGES));
        return req;
    }

    private ConversationContext build() {
        ConversationContext built = new ConversationContext();
        built.setGrade(getGrade());
        built.setDepth(Depth.IMMEDIATE);
        built.setOutputDeclaration(OutputDeclaration.of(OutputSize.STANDARD));
        OutgoingMessage<Transcription> message = new OutgoingMessage<>(new PojoResponseHandler<>(Transcription.class));
        message.setRole("user");
        message.setTimestamp(Instant.now());
        Path file = Path.of(input.getPath());
        try {
            Sniff.Kind kind = Sniff.of(file);
            if (Sniff.isImage(kind)) {
                try (InputStream in = Files.newInputStream(file)) {
                    message.addImage(in, Sniff.mime(kind), file.getFileName().toString());
                }
                pagesSent = 1;
            }
            else if (kind == Sniff.Kind.PDF) {
                try (PDDocument document = PDDocument.load(file.toFile())) {
                    pagesTotal = document.getNumberOfPages();
                    List<Integer> pages = input.getPages();
                    if (pages == null) {
                        pages = new ArrayList<>();
                        for (int page = 1; page <= pagesTotal; page++) {
                            pages.add(page);
                        }
                    }
                    PDFRenderer renderer = new PDFRenderer(document);
                    for (int page : pages.subList(0, Math.min(pages.size(), MAX_PAGES))) {
                        BufferedImage image = renderer.renderImageWithDPI(page - 1, RENDER_DPI, ImageType.RGB);
                        ByteArrayOutputStream png = new ByteArrayOutputStream();
                        ImageIO.write(image, "png", png);
                        message.addImage(new ByteArrayInputStream(png.toByteArray()), "image/png",
                                file.getFileName() + " page " + page);
                        pagesSent++;
                    }
                    pagesAsked = pages.size();
                }
            }
            else {
                throw new UncorrectableRuntimeLLMException(input.getPath() + " is neither an image nor a PDF by its bytes (" + kind
                        + "); the vision tier transcribes pictures");
            }
        }
        catch (IOException e) {
            throw new UncorrectableRuntimeLLMException("Cannot render " + input.getPath() + ": " + e.getMessage(), e);
        }
        message.addText(INSTRUCTIONS);
        built.getMessages().add(message);
        return built;
    }

    @Override
    public Extraction execute(JobResources resources, JobContext<Extraction> context) throws LLMReadableCheckedException {
        try {
            context.publish("Transcribing " + pagesSent + " image(s)", 20);
            Transcription transcription = converse(resources);
            Extraction extraction = new Extraction();
            extraction.setPath(input.getPath());
            extraction.setPages(pagesTotal != null ? pagesTotal : pagesSent);
            if (transcription.isNoText()) {
                extraction.setTextLayer(false);
                extraction.setNote("the model read no text in the image(s)");
            }
            else {
                extraction.setText(transcription.getText());
            }
            if (pagesAsked > MAX_PAGES) {
                extraction.setNote("transcribed the first " + MAX_PAGES + " of the " + pagesAsked + " pages asked for");
            }
            context.publish("Transcribed", 100);
            return extraction;
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }
}
