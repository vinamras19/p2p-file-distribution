package com.p2p.filesystem.core;

import java.security.MessageDigest;

public class FileChunk

{
    private final String fileId;
    private final int chunkIndex;
    private final byte[] data;
    private final String checksum;

    public FileChunk(String fileId, int chunkIndex, byte[] data) {
        this.fileId = fileId;
        this.chunkIndex = chunkIndex;
        this.data = data;
        this.checksum = calculateChecksum(data);
    }

    public String getChunkKey() {
        return fileId + "_" + chunkIndex;
    }

    private String calculateChecksum(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public String getFileId() { return fileId; }
    public int getChunkIndex() { return chunkIndex; }
    public int getSize() { return data.length; }
    public String getSha1Hash() { return checksum; }
    public byte[] getData() { return data; }

    @Override
    public String toString() {
        return String.format("Chunk[%s:%d | %d bytes | %s]",
                fileId, chunkIndex, data.length, checksum.substring(0, 8));
    }
}