package com.p2p.filesystem.network;

import com.p2p.filesystem.core.FileChunk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class ChunkResponseMessage extends P2PMessage {
    private final boolean success;
    private final String errorMessage;
    private final FileChunk chunk;

    // Error fields - if failed
    private final String fileId;
    private final int chunkIndex;

    // Success Constructor
    public ChunkResponseMessage(String senderId, FileChunk chunk) {
        super(Type.CHUNK_RESPONSE, senderId);
        this.success = true;
        this.errorMessage = "";
        this.chunk = chunk;
        this.fileId = chunk.getFileId();
        this.chunkIndex = chunk.getChunkIndex();
    }

    // Failure Constructor
    public ChunkResponseMessage(String senderId, String fileId, int index, String error) {
        super(Type.CHUNK_RESPONSE, senderId);
        this.success = false;
        this.errorMessage = error;
        this.fileId = fileId;
        this.chunkIndex = index;
        this.chunk = null;
    }

    public ChunkResponseMessage(String senderId, DataInputStream dis) throws IOException {
        super(Type.CHUNK_RESPONSE, senderId);
        this.success = dis.readBoolean();

        if (success) {
            String fId = readString(dis);
            int idx = dis.readInt();
            int len = dis.readInt();
            byte[] data = new byte[len];
            dis.readFully(data);

            this.chunk = new FileChunk(fId, idx, data);
            this.fileId = fId;
            this.chunkIndex = idx;
            this.errorMessage = "";
        } else {
            this.fileId = readString(dis);
            this.chunkIndex = dis.readInt();
            this.errorMessage = readString(dis);
            this.chunk = null;
        }
    }

    @Override
    protected void writePayload(DataOutputStream dos) throws IOException {
        dos.writeBoolean(success);
        if (success) {
            writeString(dos, chunk.getFileId());
            dos.writeInt(chunk.getChunkIndex());
            dos.writeInt(chunk.getSize());
            dos.write(chunk.getData());
        } else {
            writeString(dos, fileId);
            dos.writeInt(chunkIndex);
            writeString(dos, errorMessage);
        }
    }

    public boolean isSuccess() { return success; }
    public FileChunk getChunk() { return chunk; }
    public String getErrorMessage() { return errorMessage; }
}