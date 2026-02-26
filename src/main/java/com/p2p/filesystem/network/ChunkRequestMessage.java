package com.p2p.filesystem.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class ChunkRequestMessage extends P2PMessage {
    private final String fileId;
    private final int chunkIndex;

    public ChunkRequestMessage(String senderId, String fileId, int chunkIndex) {
        super(Type.CHUNK_REQUEST, senderId);
        this.fileId = fileId;
        this.chunkIndex = chunkIndex;
    }

    // Constructor for deserializer
    public ChunkRequestMessage(String senderId, DataInputStream dis) throws IOException {
        super(Type.CHUNK_REQUEST, senderId);
        this.fileId = readString(dis);
        this.chunkIndex = dis.readInt();
    }

    @Override
    protected void writePayload(DataOutputStream dos) throws IOException {
        writeString(dos, fileId);
        dos.writeInt(chunkIndex);
    }

    public String getFileId() { return fileId; }
    public int getChunkIndex() { return chunkIndex; }
}