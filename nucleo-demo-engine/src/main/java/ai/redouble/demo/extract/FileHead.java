/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.extract;

import ai.redouble.nucleo.harness.schema.*;

/**
 * What the classifier sees of a file nothing else could place: its name, its size and
 * its first bytes decoded as text, with undecodable bytes shown as the replacement
 * character. Enough to tell text under a strange extension from a binary.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-14)
 */
public class FileHead {
    @LLMDescription("The file's name with its extension")
    private String name;
    @LLMDescription("The file's size in bytes")
    private long sizeBytes;
    @LLMDescription("The first bytes of the file decoded as UTF-8; undecodable bytes appear as the replacement character")
    private String head;
    @LLMDescription("How many of the sampled bytes could not be decoded as text, out of the sample size")
    private int undecodable;
    private int sampled;

    public String getName() {return name;}

    public void setName(String name) {this.name = name;}

    public long getSizeBytes() {return sizeBytes;}

    public void setSizeBytes(long sizeBytes) {this.sizeBytes = sizeBytes;}

    public String getHead() {return head;}

    public void setHead(String head) {this.head = head;}

    public int getUndecodable() {return undecodable;}

    public void setUndecodable(int undecodable) {this.undecodable = undecodable;}

    public int getSampled() {return sampled;}

    public void setSampled(int sampled) {this.sampled = sampled;}
}
