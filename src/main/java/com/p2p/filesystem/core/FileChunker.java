package com.p2p.filesystem.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class FileChunker {
    private final int chunkSize;

    public FileChunker(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public FileChunker() {
        this(256 * 1024); // Default 256KB
    }

    public List<FileChunk> chunkFile(String fileId, Path path) throws IOException {
        List<FileChunk> chunks = new ArrayList<>();

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(chunkSize);
            int index = 0;

            while (channel.read(buffer) > 0) {
                buffer.flip();
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);

                chunks.add(new FileChunk(fileId, index++, data));
                buffer.clear();
            }
        }
        return chunks;
    }

    public int getChunkSize() {
        return chunkSize;
    }
}