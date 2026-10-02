/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/**
 * The tier that costs nothing: text the code can read without a model or a native toolkit. The
 * file's first bytes say what it is before its extension does: an Office document (a zip of XML)
 * is unzipped and its body parts are stripped of tags; anything unsigned is read as UTF-8 text,
 * and comes back with {@code textLayer} false when too much of it failed to decode, which is what
 * a binary under a text extension looks like and what the classifier then judges. Images and PDFs
 * are not read here - they go whole to the model tier - so this tier pulls no font or graphics
 * toolkit and compiles to a native image on any OS. A file this tool cannot open is a fault of
 * the run, not of a model, and propagates as such.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
@DisplayName(value = "Extract Text", action = "Reading a file")
@ToolName("extract_text")
@ToolDescription(value = "Reads the text of a file by its format, without a model.", readOnly = true)
@ToolWeight(type = ToolType.IN_MEMORY)
public class DeterministicExtractTool extends AbstractTool<FileRef, Extraction> {
    /** Extensions read as text without asking anyone. */
    public static final Set<String> TEXT = Set.of("txt", "md", "markdown", "json", "csv", "tsv", "xml", "html", "htm",
            "yaml", "yml", "properties", "ini", "toml", "log", "java", "py", "js", "ts", "sql", "sh", "rst", "adoc");
    /** Above this share of undecodable characters, a file is not text whatever its extension says. */
    static final double UNDECODABLE_SHARE = 0.05;

    public DeterministicExtractTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofMinutes(2));
    }

    public static String extension(String path) {
        String name = Path.of(path).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
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
                case OFFICE -> readOffice(file, extraction);
                case UNSIGNED -> readText(file, extraction);
                default -> throw new InvalidInputException("path", input.getPath(),
                        "a format the deterministic tier reads: text or Office, and this is " + kind);
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

    /**
     * The text of an OOXML document, read as what it is: a zip of XML. Every body part under
     * {@code word/}, {@code xl/} or {@code ppt/} has its tags stripped and its text kept; the
     * relationship, style and theme parts, which carry no document text, are skipped. This reads
     * docx, xlsx and pptx well enough to search and price without POI, so the tier needs no AWT.
     */
    private static void readOffice(Path file, Extraction extraction) throws IOException {
        extraction.setText(officeText(file));
    }

    /** The text of an OOXML document (docx, xlsx, pptx) with its tags stripped, as {@link #readOffice} reads it; for any reader of Office files that needs no POI. */
    public static String officeText(Path file) throws IOException {
        StringBuilder text = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".xml")) {
                    continue;
                }
                if (!(name.startsWith("word/") || name.startsWith("xl/") || name.startsWith("ppt/"))) {
                    continue;
                }
                if (name.contains("/_rels/") || name.contains("theme") || name.endsWith("styles.xml") || name.endsWith("settings.xml")) {
                    continue;
                }
                appendStrippedText(new String(zip.readAllBytes(), StandardCharsets.UTF_8), text);
            }
        }
        return text.toString().strip();
    }

    /** Replaces XML tags with spaces, decodes the common entities, collapses whitespace, and appends the result as a line. */
    private static void appendStrippedText(String xml, StringBuilder out) {
        String stripped = xml.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replaceAll("\\s+", " ").strip();
        if (!stripped.isEmpty()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(stripped);
        }
    }
}
