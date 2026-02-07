package com.p2p.filesystem.core;

import java.util.Collections;
import java.util.List;

public class FileMetadata

{
    private final String fileId;
    private final String fileName;
    private final long fileSize;
    private final int chunkSize;
    private final List<String> chunkHashes;

    private String merkleRoot;

    public FileMetadata(String fileId, String fileName, long fileSize, int chunkSize, List<String> chunkHashes) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.chunkSize = chunkSize;
        this.chunkHashes = chunkHashes;
    }

    public int getTotalChunks() {
        return chunkHashes.size();
    }

    public String getFileId() { return fileId; }
    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public int getChunkSize() { return chunkSize; }
    public List<String> getChunkHashes() { return Collections.unmodifiableList(chunkHashes); }
    public String getMerkleRoot() { return merkleRoot; }
    public void setMerkleRoot(String merkleRoot) { this.merkleRoot = merkleRoot; }

    @Override
    public String toString() {
        return String.format("%s (%d bytes, %d chunks)", fileName, fileSize, getTotalChunks());
    }
}