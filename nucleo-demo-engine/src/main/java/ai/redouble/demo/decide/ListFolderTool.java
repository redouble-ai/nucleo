/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.decide;

import ai.redouble.demo.extract.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.deciding.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Lists a folder: every regular file in it, by name, with what its bytes say it is and, for
 * a text file, its first words. No model: the sniffing is the extract step's, the head is a
 * few hundred bytes decoded. The list is what a decision model chooses files from, so each
 * entry carries exactly what a person would glance at in a file browser. A path that is not
 * a folder on this machine is refused with the reason ({@link InvalidInputException}).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
@ToolName("list_folder")
@ToolDescription(value = "Lists the files of a folder: name, kind (text, office, pdf, image, binary or empty), size and the first words of a text file", readOnly = true)
public class ListFolderTool extends DecisionTool<Folder, ListArtifact<FileEntry>> {
    /** How many bytes of a file decide whether it is text and give its first words. */
    static final int HEAD_BYTES = 512;
    /** Above this share of undecodable characters in the head, an unsigned file is binary. */
    static final double UNDECODABLE_SHARE = 0.05;

    public ListFolderTool(Identifiable parent) {
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
    public ListArtifact<FileEntry> execute(JobResources resources, JobContext<ListArtifact<FileEntry>> context) throws LLMReadableCheckedException {
        Path folder = Path.of(input.getPath());
        if (!Files.isDirectory(folder)) {
            throw new InvalidInputException("path", input.getPath(), "a folder on this machine");
        }
        List<FileEntry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(Files::isRegularFile).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                entries.add(entry(file));
            }
        }
        catch (IOException e) {
            throw new SystemException("list_folder", "Cannot list " + input.getPath(), e);
        }
        ListArtifact<FileEntry> list = new ListArtifact<>();
        list.setIterands(entries);
        list.setIterandTypeAlias("file");
        return list;
    }

    static FileEntry entry(Path file) throws IOException {
        FileEntry entry = new FileEntry();
        entry.setName(file.getFileName().toString());
        entry.setPath(file.toAbsolutePath().toString());
        entry.setBytes(Files.size(file));
        if (entry.getBytes() == 0) {
            entry.setKind(FileEntry.EMPTY);
            return entry;
        }
        Sniff.Kind sniffed = Sniff.of(file);
        if (Sniff.isImage(sniffed)) {
            entry.setKind(FileEntry.IMAGE);
        }
        else if (sniffed == Sniff.Kind.PDF) {
            entry.setKind(FileEntry.PDF);
        }
        else if (sniffed == Sniff.Kind.OFFICE) {
            entry.setKind(FileEntry.OFFICE);
        }
        else {
            String head = decodedHead(file);
            boolean text = DeterministicExtractTool.TEXT.contains(DeterministicExtractTool.extension(entry.getName())) || head != null;
            entry.setKind(text ? FileEntry.TEXT : FileEntry.BINARY);
            if (text && head != null) {
                entry.setHead(Digests.cut(head, Digests.HEAD_CHARS));
            }
        }
        return entry;
    }

    /** The first bytes as text, or null when too many of them do not decode: the bytes of something that is not text. */
    static String decodedHead(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(HEAD_BYTES);
        }
        String text = new String(head, StandardCharsets.UTF_8);
        int undecodable = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '�' || (c < 0x20 && c != '\n' && c != '\r' && c != '\t')) {
                undecodable++;
            }
        }
        return text.isEmpty() || (double) undecodable / text.length() > UNDECODABLE_SHARE ? null : text;
    }
}
