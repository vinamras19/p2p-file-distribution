package com.p2p.filesystem.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FileAnnounceMessage extends P2PMessage {
    private final List<String> fileIds;

    public FileAnnounceMessage(String senderId, List<String> fileIds) {
        super(Type.FILE_ANNOUNCE, senderId);
        this.fileIds = fileIds;
    }

    public FileAnnounceMessage(String senderId, DataInputStream dis) throws IOException {
        super(Type.FILE_ANNOUNCE, senderId);
        int count = dis.readInt();
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(readString(dis));
        }
        this.fileIds = ids;
    }

    @Override
    protected void writePayload(DataOutputStream dos) throws IOException {
        dos.writeInt(fileIds.size());
        for (String id : fileIds) {
            writeString(dos, id);
        }
    }

    public List<String> getFileIds() {
        return Collections.unmodifiableList(fileIds);
    }
}