/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import ai.redouble.demo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * The tier for what the code cannot read: an image, or a PDF. The file is shipped whole to a
 * model - the image as an image, the PDF as a document - and comes back as text; nothing is
 * rendered locally. The tool declares no model: it declares a grade and what it sends (images
 * or documents, by the file's bytes), so the picker resolves an entry that accepts it, whichever
 * provider serves it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
@DisplayName(value = "Read With a Model", action = "Reading a file with a model")
@ToolName("transcribe_images")
@ToolDescription(value = "Reads the text of an image or a PDF by sending the file to a document-capable model.", readOnly = true)
@ToolWeight(type = ToolType.API_CALL, min = 5, max = 5)
public class VisionExtractTool extends AbstractModelDependentTool<FileRef, Extraction> {
    public static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "gif", "webp");
    private static final String PDF_MIME = "application/pdf";
    private static final String INSTRUCTIONS = "Transcribe every word of text in this file, in reading order,"
            + " page after page, into the one string field text, pages separated by a blank line. Keep tables as rows."
            + " Do not summarize, translate or comment. If there is no readable text, say so in no_text and leave text empty.";
    private int filesSent;
    /** What the file goes to the model as, set when the conversation is built: an image as images, a PDF as a document. */
    private Input sent;

    public VisionExtractTool(Identifiable parent) {
        super(parent, Grade.SMALL);
        setTimeout(Duration.ofMinutes(6));
        setUpstreamRetries(DemoPolicy.UPSTREAM_RETRIES);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        req.setReadOnly(true);
        // wired through the base so a malformed transcription is corrected, never fatal; the
        // request declares what the file goes as, so the picker serves an entry that accepts it
        ModelBinding binding = wireConversation(req, Depth.IMMEDIATE, this::build);
        binding.setSends(Set.of(sent));
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
                filesSent = 1;
                sent = Input.IMAGES;
            }
            else if (kind == Sniff.Kind.PDF) {
                // shipped whole as a document; the model reads every page, nothing is rendered here
                try (InputStream in = Files.newInputStream(file)) {
                    message.addFile(in, PDF_MIME, file.getFileName().toString());
                }
                filesSent = 1;
                sent = Input.DOCUMENTS;
            }
            else {
                throw new UncorrectableRuntimeLLMException(input.getPath() + " is neither an image nor a PDF by its bytes (" + kind
                        + "); the model tier reads pictures and documents");
            }
        }
        catch (IOException e) {
            // requirements are captured where no checked exception can travel; the file that cannot be read is uncorrectable
            throw new UncorrectableRuntimeLLMException("Cannot read " + input.getPath() + ": " + e.getMessage(), e);
        }
        message.addText(INSTRUCTIONS);
        built.getMessages().add(message);
        return built;
    }

    @Override
    public Extraction execute(JobResources resources, JobContext<Extraction> context) throws LLMReadableCheckedException {
        try {
            context.publish("Reading " + filesSent + " file with a model", 20);
            Transcription transcription = converse(resources);
            Extraction extraction = new Extraction();
            extraction.setPath(input.getPath());
            if (transcription.isNoText()) {
                extraction.setTextLayer(false);
                extraction.setNote("the model read no text in the file");
            }
            else {
                extraction.setText(transcription.getText());
            }
            context.publish("Read", 100);
            return extraction;
        }
        catch (Exception e) {
            throw LLMReadableCheckedException.unwrap(e);
        }
    }
}
