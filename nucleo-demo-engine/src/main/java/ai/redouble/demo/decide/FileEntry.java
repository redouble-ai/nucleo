/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.decide;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.schema.*;

/**
 * One file of a folder as the listing saw it: its name, what kind of file its bytes say it
 * is, its size, and for a text file the first words. The kind and the head are what a model
 * decides on, since opening the file is a move of its own.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
@TypeAlias("file")
public class FileEntry extends AbstractArtifact {
    /** What the bytes say the file is: text, office, pdf, image, binary or empty. */
    public static final String TEXT = "text";
    public static final String OFFICE = "office";
    public static final String PDF = "pdf";
    public static final String IMAGE = "image";
    public static final String BINARY = "binary";
    public static final String EMPTY = "empty";
    @LLMDescription("The file's name")
    private String name;
    @LLMDescription("What the bytes say the file is: text, office, pdf, image, binary or empty")
    private String kind;
    @LLMDescription("The file's size in bytes")
    private long bytes;
    @LLMDescription("The first words of a text file, null for any other kind")
    private String head;
    @LLMDescription("The file's absolute path")
    private String path;

    public String getName() {return name;}

    public void setName(String name) {this.name = name;}

    public String getKind() {return kind;}

    public void setKind(String kind) {this.kind = kind;}

    public long getBytes() {return bytes;}

    public void setBytes(long bytes) {this.bytes = bytes;}

    public String getHead() {return head;}

    public void setHead(String head) {this.head = head;}

    public String getPath() {return path;}

    public void setPath(String path) {this.path = path;}
}
