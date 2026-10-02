/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.decide;

import ai.redouble.demo.extract.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.deciding.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;

/**
 * Reads a file as text: a text file decoded, an Office document with its tags stripped. A
 * PDF, an image, a binary or an empty file is refused with the reason, which a decision
 * thinker feeds back to its model as a fact of the run: this palette reads what code can
 * read, and the model learns which files those are.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
@ToolName("read_document")
@ToolDescription(value = "Reads a text file or an Office document (docx, xlsx, pptx) as text; refuses a PDF, an image, a binary or an empty file", readOnly = true)
public class ReadDocumentTool extends DecisionTool<FileEntry, Document> {
    /** Above this share of undecodable characters, a file is not text whatever its extension says. */
    static final double UNDECODABLE_SHARE = 0.05;

    public ReadDocumentTool(Identifiable parent) {
        super(parent);
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements requirements = new JobRequirements();
        requirements.setRequiresTransaction(false);
        requirements.setReadOnly(true);
        return requirements;
    }

    @Override
    public Document execute(JobResources resources, JobContext<Document> context) throws LLMReadableCheckedException {
        Path file = Path.of(input.getPath());
        Document document = new Document();
        document.setName(input.getName());
        document.setPath(input.getPath());
        try {
            switch (input.getKind()) {
                case FileEntry.TEXT -> document.setText(text(file));
                case FileEntry.OFFICE -> document.setText(DeterministicExtractTool.officeText(file));
                case FileEntry.PDF, FileEntry.IMAGE -> throw new InvalidInputException("file", input.getName(),
                        "a text file or an Office document; " + input.getName() + " is " + (input.getKind().equals(FileEntry.PDF) ? "a PDF" : "an image")
                                + ", which needs a model that can see, and this palette has none");
                case FileEntry.BINARY -> throw new InvalidInputException("file", input.getName(),
                        "a text file or an Office document; " + input.getName() + " is binary, there is no text in it");
                case FileEntry.EMPTY -> throw new InvalidInputException("file", input.getName(), "a file with something in it; " + input.getName() + " is empty");
                default -> throw new InvalidInputException("file", input.getName(), "a file of a kind the listing names; " + input.getKind() + " is not one");
            }
        }
        catch (IOException e) {
            throw new SystemException("read_document", "Cannot read " + input.getPath(), e);
        }
        return document;
    }

    /** The file decoded as UTF-8, malformed bytes replaced; refused as not text past the undecodable share. */
    private String text(Path file) throws IOException, InvalidInputException {
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
            throw new InvalidInputException("file", input.getName(), "a text file; " + undecodable + " of " + text.length()
                    + " characters of " + input.getName() + " do not decode, so it is not one");
        }
        return text.toString();
    }
}
